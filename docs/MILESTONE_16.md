# Milestone 16: Item Quantities, Pluralization, and `all`

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md` — ``### Quantities and `all` `` (under `## Items`, line 199).
- **Design Summary:**
  - **Keyword phrases:** `Item.matches(phrase)` accepts a phrase of one or more words. Articles (`a`, `an`, `the`,
    `some`) are dropped from the phrase unless it is only articles (so `an` still abbreviates `anvil`), and every
    remaining word must prefix-match some keyword of the item whole, a word of one of its keywords, or a word of its
    `plural` (`ItemProto.matchWords`). So `get gold coin`, `get coins` and `get the coin` all find `a gold coin`, and a
    declared keyword such as `long-sword` still matches when typed in full. Because `World.findItem` uses
    `Item.matches`, item commands that pass their whole argument to it gain phrase matching; `use` still takes its item
    from the first word only. `Actor.matches` is unchanged.
  - **Pluralization and stack display (`plural`):** `item(...)` declarations gain an optional `plural` string
    (`ItemProto.plural: String? = null`), exposed as `item.plural` on the Starlark item view and as `PluginItem.plural`.
    Words of `plural` are matched by `Item.matches` only. They are not added to `ItemProto.keywords`, so `item.keywords`
    shows exactly what was declared or defaulted. `Item.name` stays the singular name (`nameOverride ?: proto.name`).
    `Item.describe(amount: Int = count): String` formats quantities for player output:
    - `name` when `amount == 1`;
    - `"$amount ${proto.plural}"` when `amount > 1`, `nameOverride == null`, and `proto.plural != null`;
    - `"$name ($amount)"` otherwise.
  - **Bare `all` exclusion:** bare `all` (`get all`, `get all [from] <container>`, `drop all`, `put all [in|into]
    <container>`, `give all [to] <target>`) skips an item when `"no_all" in item.tags` or `item.proto.isKeyOnly`.
    `Prototypes.CORPSE` declares `tags = setOf("no_all")`. Keyring-only keys are skipped by the `isKeyOnly` check, not
    by a tag, so their `tags` are unchanged. `put` also never moves the target container itself. Explicit keywords (`all
    <keyword>`, `<count> <keyword>`, `<keyword>`) ignore both exclusions.
  - **No in-place stack splitting:** `World.splitItem` and `ctx.split` are removed. Instead, `World.moveItem(item, to,
    count = item.count): Item` and `World.destroy(item, count = item.count)` take a count, and so do the script verbs
    `ctx.move_item(item, to, index = 0, count = None)`, `ctx.give(actor, label_or_item, count = None)` and
    `ctx.destroy(item, count = None)`. Part of a stack moves or is destroyed in one step, without leaving an unmerged
    twin stack at the source. A moved part does not fire `on_spawn`, because no new content was created.
  - **Command syntax:** `get <what> [[from] <container>]`, `drop <what>`, `give <what> [to] <target>`, and `put <what>
    [in|into] <container>` resolve their items when the action runs in the tick lane (`validate` / `resolve`), not when
    the command is planned. `<what>` is one of: `<keyword>`, `<ordinal>.<keyword>`, `<count> <keyword>`, `<count>
    <ordinal>.<keyword>`, `all`, or `all <keyword>`. There is no `all.<keyword>` syntax. In `get`, `all <word>` where
    `<word>` names a reachable container means "everything in that container" (`get all corpse` loots the corpse); to
    pick up a corpse itself, use `get corpse`.
  - **Capping and one cycle per stack:** a requested count is capped to what the source holds, and to what fits at the
    destination: the actor's `carry_limit` for `get`, the receiver's `carry_limit` for `give`, and the container's
    `capacity` and `max_weight` for `put`. `get` from a container the actor already carries is not capped by
    `carry_limit`, since the actor's load doesn't change. Each source stack goes through one cycle: `get` and `drop` run
    their `before_*` hook, then `moveItem(item, to, n)`, the messages, the `on_*` hook (`put` has none), and
    `stack(moved)`. `give` and `put` have no `before_*` hook, as today. When the transfer is capped by weight or
    capacity, the existing message (`CANT_CARRY`, `GIVE_FULL`, `PUT_FULL`, or `PUT_WONT_FIT`) is sent once at the end.
  - **Hooks see the source stack:** `before_get` and `before_drop` receive the whole source stack as `item`; the number
    of units being moved is not exposed. `on_get`, `on_drop` and `on_give` receive the moved item.
  - **No new message IDs.** `get` that finds nothing says `DONT_SEE`. `drop`, `give` and `put` that find nothing in the
    inventory say `NOT_CARRYING`, including `drop` with no argument (unchanged from today).
- **Specification Impact:** `docs/SPEC.md` `§3.3` (Item), `§4.2` (Content Declarations: `item`), `§4.5` (Item view and
  Item verbs), `§4.9` (Commands: `inventory`, `keyring`, `look`, `get`, `drop`, `give`, `put`), `§4.15` (PluginItem),
  `§7.4` (M16 row).
- **Decision Record Updates:** None. All prior `D-xxx` entries in `docs/DECISIONS.md` are already ratified and folded
  into `docs/SPEC.md`.

---

## Standing Rules for All Phases

1. **Read `AGENTS.md` first.** Run every `./gradlew` command with `BypassSandbox: true` so the Gradle client can connect
   to the local daemon.
2. **No spec or decision citations in code.** KDoc and comments in `.kt` files must never contain `§`, `SPEC`, or
   decision IDs such as `D-005`. State behavior directly.
3. **Every phase must end green.** Do not start the next phase until every exit condition of the current phase passes.
4. **Markdown formatting.** Whenever a `.md` file changes, run `./gradlew markdownFormat`, then verify `./gradlew
   markdownCheck`.
5. **Golden files.** Whenever the script API changes, regenerate all three API files and commit them:
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/api.json`.
   - `./gradlew :cli:installDist && cli/build/install/gups/bin/gups docs --update-baseline` rewrites
     `docs/api/api-1.json`.
6. **Warnings are errors.** Kotlin warnings fail the build, including "extension is shadowed by a member".

---

## Phase 1: Phrase Matching, `plural`, CORPSE `no_all`, and Stack Display

### Objective & Scope

- **In Scope:**
  - Move the keyword word-splitting helper and the article list into core, and make `Item.matches` match phrases and
    `plural` words.
  - Add `plural: String? = null` to `ItemProto`, to `item(...)` in Starlark declarations, to `ItemView`, and to
    `PluginItem`.
  - Add `tags = setOf("no_all")` to `Prototypes.CORPSE`.
  - Add `Item.describe(amount)` and use it in `look` (room items and open containers), `inventory`, `keyring`, and the
    `get`, `drop`, `give` and `put` messages.
  - Update the API golden and baseline files, `docs/CHANGELOG.md`, and `docs/SPEC.md` (`§3.3`, `§4.2`, `§4.5`, `§4.9`,
    `§4.15`).
- **Out of Scope:**
  - Partial counts on `World.moveItem` / `World.destroy`, and removing `ctx.split` (Phase 2).
  - Parsing `<count>` and `all` in `get`, `drop`, `give`, and `put` (Phases 3–4).
  - The bare-`all` exclusion check itself (Phase 3).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Messages.kt` (`Messages.fill` capitalizes a placeholder at the start of a
    message, so `"5 gold coins"` and `"a gold coin"` both read correctly at a sentence start)
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
  - `cli/src/test/resources/docs-golden/api.json`
  - `docs/api/api-1.json`
  - `docs/CHANGELOG.md`
  - `docs/SPEC.md`
- **Do Not Touch:**
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`
  - `script/src/main/kotlin/gups/script/Ctx.kt`

### Specification & Decision Record Changes

Update `docs/SPEC.md`:

- **`§3.3` (Item), under "Quantities and decay":** add that an item prototype may declare an optional `plural` display
  name, used to show stacks of 2 or more as `"<count> <plural>"`, and that bare `all` in item commands skips items
  tagged `no_all` (such as `//engine:corpse`) and keyring-only keys.
- **`§4.2` (`item` declaration):**
  - Add `plural = None` to the `item(...)` example.
  - Under "Defaults", add: "An item may also give `plural` (for example `plural = "gold coins"`), used when showing
    stacks of 2 or more. Players can name an item by the words of its keywords or of its plural."
  - Under "Tags", add: "`no_all` keeps an item out of bare `all` in `get`, `drop`, `give` and `put`. `//engine:corpse`
    carries it."
- **`§4.5` (Item view):** add `plural` after `name`.
- **`§4.9` (`look`, `inventory`, `keyring`):** add that stacks of 2 or more show as `"<count> <plural>"` when the
  prototype has a `plural` and the item has no name override, or as `"<name> (<count>)"` otherwise. Add that item
  keywords can be phrases: every word must match the start of one of the item's keywords or plural words, and articles
  are ignored.
- **`§4.15` (PluginItem):** add `plural`.

### Implementation Blueprint

- **Shared keyword words (`core/src/main/kotlin/gups/core/model/Entities.kt`, top level, public):**

  ```kotlin
  /** Articles ignored when deriving or matching item keywords. */
  val KEYWORD_ARTICLES: Set<String> = setOf("a", "an", "the", "some")

  private val WORD_SPLIT = Regex("[^a-z0-9]+")

  /** The lowercase words of [text], minus articles. */
  fun keywordWords(text: String): List<String> =
    text.lowercase().split(WORD_SPLIT).filter { it.isNotEmpty() && it !in KEYWORD_ARTICLES }
  ```

  In `script/src/main/kotlin/gups/script/Declarations.kt`, delete the private `ARTICLES` and rewrite `defaultKeywords`
  to reuse it, with identical behavior:

  ```kotlin
  private fun defaultKeywords(name: String, id: String): List<String> =
    keywordWords(name).ifEmpty { listOf(id.lowercase()) }
  ```
- **`ItemProto` (`Protos.kt`):** add a last constructor parameter:

  ```kotlin
  /** Display name for stacks of 2 or more, such as `gold coins`, or null to show `name (count)`. */
  val plural: String? = null,
  ```

  In `Prototypes.CORPSE`, add `tags = setOf("no_all"),`. Do not change `isKeyOnly` or move it.

- **`Item` (`Entities.kt`):**

  ```kotlin
  /**
   * How players see [amount] of this item (by default the whole stack): [name] for one,
   * `"<amount> <plural>"` when the prototype has a plural and there is no [nameOverride], else
   * `"<name> (<amount>)"`.
   */
  fun describe(amount: Int = count): String =
    when {
    amount <= 1 -> name
    nameOverride == null && proto.plural != null -> "$amount ${proto.plural}"
    else -> "$name ($amount)"
    }

  /**
   * True if every word of [phrase], minus articles, is the start of one of this item's keywords
   * or plural words, ignoring case.
   */
  fun matches(phrase: String): Boolean {
    val words = keywordWords(phrase).ifEmpty { allWords(phrase) }
    if (words.isEmpty()) return false
    return words.all { w -> proto.matchWords.any { it.startsWith(w) } }
  }
  ```

  As implemented, `ItemProto.matchWords` (computed once per prototype) holds each keyword whole, each keyword's own
  words, and the plural's words, and an article-only phrase falls back to its raw words. `item(plural = "")` fails to
  load, and `GetAction` applies `ordinal()` before matching.

  `keywordWords` splits on any non-alphanumeric character, so the existing `ordinal()` helper in `KernelCommands.kt`
  must keep stripping the `2.` prefix before calling `matches` (it already does).

- **`Reconcile.kt`:** in `tombstone(was)`, add `plural = was.plural`. Do not copy `tags`.

- **`KernelCommands.kt`:** replace `it.name` / `item.name` with `describe()` in these places only:

  - `look` at an open container (line 255): `inside.joinToString(", ") { it.describe() }`.
  - `inventory` (line 386) and `keyring` (line 400): `"item" to it.describe()`.
  - `describeRoom`, room items without a track (line 1279) and items by band (line 1312).
  - The `"item"` placeholder of `GET_SELF`, `GET_SELF_FROM`, `GET_ROOM`, `DROP_SELF`, `DROP_ROOM`, `GIVE_SELF`,
    `GIVE_TARGET`, `GIVE_ROOM`, `PUT_SELF` and `PUT_ROOM`. Compute `val desc = item.describe()` before any call to
    `w.stack(item)`, because `stack` can merge the item into another stack and destroy it.

  Leave `CANT_REACH`, `PUT_WONT_FIT`, `NOT_CONTAINER`, and all equipment and container-lock messages on `name`.

- **`Declarations.kt`:**

  - Add `DeclParam("plural", "str", "Display name for stacks of 2 or more, such as 'gold coins'.", default = "None")` to
    `ITEM_SPEC`, right after `"name"`.
  - In `DeclarationBuiltins.item`, read `val plural = d.str("plural")` and pass `plural = plural` to `ItemProto`. Do not
    change how `keywords` defaults.
- **`Views.kt`, on `ItemView`:**

  ```kotlin
  @StarlarkMethod(
    name = "plural",
    structField = true,
    allowReturnNones = true,
    doc = "Display name for stacks of 2 or more, or None.",
  )
  fun plural(): Any? = read { item.proto.plural }
  ```
- **`plugin-api/.../Facade.kt`:** add `val plural: String?` to `interface PluginItem`, documented like the Starlark
  field. In `plugin-api/.../host/Facade.kt`, `class ItemFacade` implements it as `override val plural: String? get() =
  item.proto.plural`.

### Test & Documentation Requirements

- In `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`:
  - Give the `coin` fixture `plural = "gold coins"`. Add a second stackable fixture with no plural, such as
    `ItemProto(pebble, "a pebble", listOf("pebble"), stackable = true)`.
  - `describe()`: a coin with `count == 1` is `"a gold coin"`. Five coins are `"5 gold coins"`. Five pebbles are `"a
    pebble (5)"`. Five coins with `nameOverride = "a lucky coin"` are `"a lucky coin (5)"`.
  - `matches`: a coin matches `"coins"`, `"gold coin"`, `"the coin"` and `"go co"`, but not `"silver coin"` or `"the"`.
  - `inventory` shows `"5 gold coins"` and `"a pebble (5)"`, and `look` shows them on the ground and in an open chest.
  - `get gold coin` picks up a coin from the ground (this fails today).
  - `"no_all" in Prototypes.CORPSE.tags`.
- In `script/src/test/kotlin/gups/script/LoaderTest.kt`: declare `item("loaf", name = "a loaf of bread", plural =
  "loaves of bread", stackable = True)`, and assert that `item.plural == "loaves of bread"` and that `item.keywords ==
  ["loaf", "of", "bread"]` (plural words are not added).
- Regenerate the golden and baseline files (Standing Rule 5).
- In `docs/CHANGELOG.md`, add a section `### Item quantities, pluralization, and all` under `## api_version 1
  (unreleased)`. It documents `item(plural=...)`, `item.plural`, `PluginItem.plural`, phrase matching for item keywords,
  stack display, and the `no_all` tag on corpses.

### Exit Conditions

- [] `./gradlew markdownFormat && ./gradlew check` (with `BypassSandbox: true`) exits `0` with zero warnings.
- [] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns `0` matches.
- [] `grep -n "ARTICLES" script/src/main/kotlin/gups/script/Declarations.kt` returns `0` matches.
- [] `ApiDiffTest`, `LoaderTest`, and `ItemCommandsTest` pass.

---

## Phase 2: Replace `split` / `splitItem` with `count` on `moveItem`, `give`, and `destroy`

### Objective & Scope

- **In Scope:**
  - Remove `World.splitItem` from `core/src/main/kotlin/gups/core/world/Verbs.kt` and `ctx.split` from
    `script/src/main/kotlin/gups/script/Ctx.kt`.
  - Add `count` to `World.moveItem` (which now returns the moved `Item`) and to `World.destroy`.
  - Add an optional `count` to `ctx.move_item` (which now returns the moved item view), `ctx.destroy`, and `ctx.give`
    with an item view.
  - Update `HostTest.kt`, `docs/SPEC.md` (`§4.5`), `docs/CHANGELOG.md`, and the API golden and baseline files.
- **Out of Scope:**
  - Player command parsing for `get`, `drop`, `give`, and `put` (Phases 3 and 4).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Invariants.kt`
  - `core/src/main/kotlin/gups/core/world/Persistence.kt` (`ItemEvent`)
  - `persist/src/test/kotlin/gups/persist/PersistenceTest.kt`
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/Verbs.kt`
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `script/src/test/kotlin/gups/script/HostTest.kt`
  - `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`
  - `cli/src/test/resources/api.golden.json`
  - `cli/src/test/resources/docs-golden/api.json`
  - `docs/api/api-1.json`
  - `docs/CHANGELOG.md`
  - `docs/SPEC.md`
- **Do Not Touch:**
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt` (its `moveItem` and `destroy` calls keep compiling, because
    `count` has a default and the new `Item` return value can be ignored)

### Specification & Decision Record Changes

Update `docs/SPEC.md` `§4.5`:

- In the **Verbs (v0)** table, `Items` row, replace `give(actor, label_or_item, count=1)`, `move_item(item, to)`,
  `destroy(item)`, and `split(item, n)` with `give(actor, label_or_item, count=None)`, `move_item(item, to, index=0,
  count=None)`, and `destroy(item, count=None)`. Keep `spawn(label, at, index=0, count=1)` as it is.
- In **Verb semantics**, under `Items`:
  - `give`: "`give(actor, label_or_item, count=None)` spawns `count` of a label (default 1), or moves `count` of an item
    (default the whole stack), into the actor's inventory. It returns the item view, or a list of views when it spawns
    several non-stackable items, and fires no hooks."
  - `move_item`: "`move_item(item, to, index=0, count=None)` moves the item, or `count` of a stack, and returns the
    moved item's view. A moved part is a new item with the stack's attributes, name override, debug flag and decay
    deadline, and doesn't fire `on_spawn`. Moving part of an equipped or non-stackable item is an error."
  - `destroy`: "`destroy(item, count=None)` removes the item and everything inside it, or, when `count` is less than the
    stack's count, removes that many from the stack."
  - Remove the `split` bullet, and remove `split,` from the paragraph that starts "Verbs without a player command...".

### Implementation Blueprint

- **Signatures (`Items.kt`):**

  ```kotlin
  /**
   * The `move_item` verb, and the single writer of [Item.location]: moves [count] of [item] (by
   * default the whole stack) to [to] and returns the item now at [to]. Moving part of a stack
   * leaves the rest in place and creates a new item with the same attributes, name override,
   * debug flag and decay deadline.
   */
  fun World.moveItem(item: Item, to: ItemLocation, count: Int = item.count): Item

  /**
   * The `destroy` verb: removes [item] and everything inside it, or only [count] of a stack when
   * [count] is less than its count.
   */
  fun World.destroy(item: Item, count: Int = item.count)
  ```
- **Algorithmic Steps & Invariants:**
  1. **`World.moveItem(item, to, count)`:**
     - `require(item.id in entities) { "$item is not in the world" }`
     - `require(count in 1..item.count) { "move_item: count must be between 1 and ${item.count}, got $count" }`
     - If `count == item.count`: run the existing whole-item logic unchanged, then `return item`.
     - If `count < item.count`:
       - `require(item.proto.stackable) { "${item.name} is not stackable" }`
       - `require(item.location !is ItemLocation.Equipped && to !is ItemLocation.Equipped) { "move_item: can't move part
         of a stack to or from equipment" }`
       - `checkTarget(item, to)`
       - `val from = item.location` and `val before = custodian(item) as? Player`.
       - Create `val part = Item(allocateId(), item.proto, to, count)`, and copy `debug`, `nameOverride`, `decayAt` and
         `customAttributes` (`part.customAttributes.putAll(item.customAttributes)`). Do not copy timers. Set `part.dirty
         = true`.
       - `item.count -= count` and `item.dirty = true`.
       - `register(part)`, then `index(part, to)`.
       - `val after = custodian(part) as? Player`.
       - Audit trail. The part is a new id, so it always needs an origin record when a player is involved: if `(before
         != null || after != null) && !part.debug`, record `itemEvents += ItemEvent(part.id, pulse, from, to, "split")`.
         If `before != null && after != null && before !== after`, call `requestImmediate("transfer")`.
       - `holderOf(from)?.let { updateEncumbrance(it) }` and `custodian(part)?.let { updateEncumbrance(it) }`.
       - Do not call `invokeOn(..., HookName.ON_SPAWN, ...)`.
       - `return part`.
  2. **`World.destroy(item, count)`:**
     - `require(item.id in entities) { "$item is not in the world" }`
     - `require(count in 1..item.count) { "destroy: count must be between 1 and ${item.count}, got $count" }`
     - If `count == item.count`: run the existing full-removal logic unchanged.
     - If `count < item.count`:
       - `val holder = custodian(item)`
       - If `holder is Player && !item.debug`, record `itemEvents += ItemEvent(item.id, pulse, item.location,
         item.location, "reduce")`, so every change to a player-held stack leaves a row.
       - `item.count -= count`, `item.dirty = true`, and `holder?.let { updateEncumbrance(it) }`.
  3. **`Verbs.kt`:** delete `World.splitItem` and its KDoc.
  4. **`Ctx.kt`:**
     - Delete `fun split(...)`, its `@StarlarkMethod`, and `import gups.core.world.splitItem`.
     - **`give`:** change the `count` param to `defaultValue = "None"`, with the doc "Number of items to spawn or move;
       defaults to 1 for a label, or the whole stack for an item."
       - Item view: `val i = itemArg(labelOrItem, "give(item)")`, `val n = intOrNull(count, "give(count)") ?: i.count`,
         `if (n !in 1..i.count) fail("give(count): must be between 1 and ${i.count}")`, then `return@verb
         v(world.moveItem(i, to, n))`.
       - Label: `val n = intOrNull(count, "give(count)") ?: 1`; the rest is unchanged.
     - **`move_item`:** add `Param(name = "count", named = true, defaultValue = "None", doc = "Number of items of the
       stack to move, or None for the whole stack.")` and a `count: Any` parameter before `thread`. Keep the existing
       `trackIndex("move_item", index)` call. Then `val i = itemArg(item, "move_item(item)")`, `val n = intOrNull(count,
       "move_item(count)") ?: i.count`, `if (n !in 1..i.count) fail("move_item(count): must be between 1 and
       ${i.count}")`, and `v(world.moveItem(i, location(to, index, "move_item(to)", thread), n))`. Update the method doc
       to say it returns the moved item.
     - **`destroy`:** add the same kind of `count` param ("Number of items of the stack to remove, or None for the whole
       stack."). Then `val i = itemArg(item, "destroy(item)")`, `val n = intOrNull(count, "destroy(count)") ?: i.count`,
       `if (n !in 1..i.count) fail("destroy(count): must be between 1 and ${i.count}")`, `world.destroy(i, n)`, and
       return `Starlark.NONE`.

### Test & Documentation Requirements

- In `script/src/test/kotlin/gups/script/HostTest.kt`, in `item, door and query verbs` (line 404):
  - Replace the two lines `part = ctx.split(coins, 2)` and `ctx.move_item(part, ctx.room, index = 0)` with `part =
    ctx.move_item(coins, ctx.room, index = 0, count = 2)`. The returned view has count 2 and `coins` drops to 3.
  - Then add `ctx.give(a, part, count = 1)` (moves 1 of the ground part into `a`'s inventory as a new stack; do not give
    `coins` to `a`, which already holds it) and `ctx.destroy(coins, count = 1)`.
  - Add `part=%d` (with `part.count`) to the `ctx.send` line. The expected line becomes `coins=2 part=1 ground=1 ...`
    (the gem is destroyed, so the ground holds only `part`); update the `assertEquals` string to match.
  - Add error tests: `count = 0` and `count = coins.count + 1` on `move_item`, `give` and `destroy` each raise a script
    error.
- In `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`:
  - `moveItem(stack, Carried(p), 2)` from the ground records one `"split"` event for the part, and none for the source.
  - `destroy(stack, 2)` on a carried stack records one `"reduce"` event and updates encumbrance.
  - Moving part of a non-stackable or equipped item throws `IllegalArgumentException`.
- Regenerate the golden and baseline files (Standing Rule 5). In `docs/CHANGELOG.md`, record the removal of `ctx.split`,
  the new `count` parameters, `move_item` returning the moved view, and that a moved part fires no `on_spawn`.

### Exit Conditions

- [] `./gradlew markdownFormat && ./gradlew check` (with `BypassSandbox: true`) exits `0` with zero warnings.
- [] `grep -rn "splitItem" --include='*.kt' .` returns `0` matches, and `grep -rn '"split"' cli/src/test/resources
  docs/api` returns `0` matches.
- [] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns `0` matches.

---

## Phase 3: Item Specifiers, Optional `from`, and `get` / `drop` with Quantities and `all`

### Objective & Scope

- **In Scope:**
  - Add a shared item-specifier parser (`ItemSpec`, `parseItemSpec`) and pool matcher (`matchItems`) to
    `core/src/main/kotlin/gups/core/world/KernelCommands.kt`.
  - Make `from` optional in `get <what> [[from] <container>]`, with the container-versus-ground rule below.
  - Rewrite `GetAction` and the drop action so that they resolve items when they run, collect across several stacks, cap
    counts, and run one hook / move / message / stack cycle per source stack.
  - Update `scavenge` in `Behaviors.kt` for the new `GetAction` constructor.
- **Out of Scope:**
  - `give` and `put` (Phase 4).
  - New message IDs. There are none.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Items.kt`
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (the action lifecycle: `validate`, `veto`, `resolve`)
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt`
  - `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`
  - `docs/CHANGELOG.md`
  - `docs/SPEC.md`
- **Do Not Touch:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`

### Specification & Decision Record Changes

Update `docs/SPEC.md` `§4.9` ("Item and combat command details"):

- Document the `<what>` syntax that `get`, `drop`, `give` and `put` share: `<keyword>`, `<ordinal>.<keyword>`, `<count>
  <keyword>`, `<count> <ordinal>.<keyword>`, `all`, and `all <keyword>`. A keyword may be a phrase.
- Document these behaviors:
  - `from` in `get <what> [[from] <container>]` is optional.
  - `get all <container>` loots the container.
  - Bare `all` skips items tagged `no_all` (such as corpses) and keyring-only keys.
  - A count is capped to what is there and to what the actor can carry, and the actor is told "You can't carry that
    much." when the carry limit capped it.
  - Each source stack runs `before_get` / `before_drop`, the move, and `on_get` / `on_drop` once.

### Implementation Blueprint

- **Types and helpers (`KernelCommands.kt`, internal, top level):**

  ```kotlin
  /**
   * A parsed `<what>`: [all] for `all` and `all <keyword>`, [count] for an explicit unit count,
   * [ordinal] for `2.sword` (default 1), and [keyword] (null only for bare `all`).
   */
  internal data class ItemSpec(
    val all: Boolean,
    val count: Int?,
    val ordinal: Int,
    val keyword: String?,
  )

  /** True if bare `all` may pick [this] up: not tagged `no_all` and not a keyring-only key. */
  internal val Item.inBareAll: Boolean
    get() = "no_all" !in tags && !proto.isKeyOnly
  ```
- **Algorithmic Steps & Invariants:**
  1. **`parseItemSpec(raw: String): ItemSpec?`:**
     - `val t = raw.trim()`. If `t` is empty, return `null`.
     - If `t.equals("all", ignoreCase = true)`, return `ItemSpec(all = true, count = null, ordinal = 1, keyword =
       null)`.
     - `val (first, rest) = splitFirst(t)`.
     - If `first.equals("all", ignoreCase = true)`, return `ItemSpec(true, null, 1, rest.lowercase())`. (`rest` is not
       empty here.)
     - If `first.toIntOrNull()` is an `n`: if `n < 1` or `rest.isEmpty()`, return `null`. Otherwise `val (ord, word) =
       ordinal(rest)`; if `ord < 1` or `word.isEmpty()`, return `null`; else return `ItemSpec(false, n, ord, word)`.
     - Otherwise `val (ord, word) = ordinal(t)`; if `ord < 1` or `word.isEmpty()`, return `null`; else return
       `ItemSpec(false, null, ord, word)`.
  2. **`matchItems(pool: List<Item>, spec: ItemSpec, exclude: Item? = null): List<Pair<Item, Int>>`:** returns `(stack,
     units wanted from it)`.
     - `val hits = pool.filter { it !== exclude && if (spec.keyword == null) it.inBareAll else it.matches(spec.keyword)
       }.drop(spec.ordinal - 1)`.
     - If `spec.all`: return `hits.map { it to it.count }`.
     - If `spec.count == null`: return `hits.take(1).map { it to it.count }` (one item, or one whole stack).
     - Otherwise walk `hits`, taking `minOf(remaining, it.count)` from each until `remaining == 0`. This caps the count
       to what is there.
  3. **`planGet(c)`:**
     - If `c.args.isBlank()`, tell `KernelMsg.GET_WHAT` and return `null`.
     - Change the `get` command's `help` string in the command table near the top of `KernelCommands` (not in `planGet`)
       to end with `"Usage: get <item|count item|all [item]> [[from] <container>]"`.
     - Return `GetAction(c.args.trim())`.
  4. **`GetAction` constructor:** `internal class GetAction(private val args: String, private val fixed: Item? = null)`.
     - `scavenge` in `Behaviors.kt` (line 241) becomes `GetAction("", fixed = item)`. When `fixed != null`, `args` is
       ignored: the source pool is `fixed` alone (if it is still `InRoom` and present), the wanted count is
       `fixed.count`, and there is no container.
     - Delete the existing `veto()` override. `before_get` now runs inside `resolve`, per stack; keeping `veto()` would
       fire it twice.
     - Private state, set in `validate` and used by `resolve`: `spec: ItemSpec?`, `container: Item?`.
  5. **Resolving `args` into `spec` and `container` (start of `validate`, when `fixed == null`):**
     - **Explicit `from`:** if `Regex("\\s+from\\s+", RegexOption.IGNORE_CASE).findAll(args).lastOrNull()` matches,
       `whatStr` is the text before it and `fromStr` the text after.
       - `spec = parseItemSpec(whatStr) ?: return w.msg(KernelMsg.GET_WHAT)`
       - `container = w.findItem(actor, fromStr, inventory = true, ground = true) ?: return w.msg(KernelMsg.DONT_SEE)`
     - **No `from`, one word:** `spec = parseItemSpec(args) ?: return w.msg(KernelMsg.GET_WHAT)` and `container = null`.
     - **No `from`, two or more words:** split off the last word, giving `beforeLast` and `lastWord`. Let `box =
       w.findItem(actor, lastWord, inventory = true, ground = true)?.takeIf { it.container != null }` and `boxSpec =
       parseItemSpec(beforeLast)`.
       - If `box == null` or `boxSpec == null`, the whole of `args` is the item: `spec = parseItemSpec(args) ?: return
         w.msg(KernelMsg.GET_WHAT)` and `container = null`.
       - If `boxSpec.all` is true, the container wins (`get all chest`, `get all coin chest`).
       - Otherwise, the container wins unless both of these hold: the box finds nothing (it is closed, or
         `matchItems(box.contents, boxSpec)` is empty), and `parseItemSpec(args)` finds something on the ground
         (`matchItems(w.roomOf(actor).items, it)` is not empty). In that case the whole phrase is a ground item.
       - Worked examples, in a room with an open chest holding a coin, and a coin on the ground:
         - `get coin chest`: the chest holds a coin, so the coin comes from the chest.
         - `get gold coin`: `coin` is not a container, so the coin comes from the ground.
         - `get coin chest` with the chest closed: "coin chest" matches nothing on the ground, so the container wins and
           `validate` reports `PUT_CLOSED`.
  6. **Rest of `GetAction.validate(w, actor)`:**
     - Source pool: `listOf(fixed)` (see step 4); `room.items` when `container == null`; otherwise check the container
       in this order, then use `container.contents`:
       - present (`DONT_SEE`);
       - is a container (`NOT_CONTAINER`);
       - is `OPEN` (`PUT_CLOSED`);
       - reachable when on the ground (`w.reach(actor, container)?.let { return it }`).
     - `val matches = matchItems(pool, spec)`. If it is empty, return `w.msg(KernelMsg.DONT_SEE)`.
     - When `Feature.TRACK in w.features` and no matched item is in reach (`getLag` returns null for each), return
       `w.msg(KernelMsg.CANT_REACH, "item" to matches.first().first.name)`. Compute each band as today: `val ground =
       container ?: item` and `val band = ground.groundIndex?.let { Band.between(w.indexOf(actor), it) } ?: Band.MELEE`.
       A carried container has no `groundIndex`, so never use `!!` here.
     - `val carriedSource = container != null && w.custodian(container) === actor`. Unless `carriedSource` is true, if
       no matched item can fit even 1 unit within `carry_limit`, return `w.msg(KernelMsg.CANT_CARRY)`. A unit weighs
       `item.proto.weight` for a stackable item, or `item.weight` otherwise.
     - Return `null`.
  7. **`GetAction.resolve(w, actor)`:**
     - Recompute `matches` from the same pool. `var maxLag = 0` and `var capped = false`.
     - For each `(item, wanted)`:
       - Skip the item if it is no longer at the source (`InRoom` in the actor's room, or `Contained(container.id)`).
       - With the track: compute `band` from `container ?: item` as in step 6 (falling back to `Band.MELEE`). `val lag =
         w.rules.getLag(RulesContext(w, actor, item = item, band = band), band)`. If `lag == null`, tell `CANT_REACH`
         with `"item" to item.name` and continue.
       - `val veto = w.dispatch(HookName.BEFORE_GET, item, actor.room, HookContext(actor = actor, item = item))`. If it
         is not null, `w.send(actor, veto)` and continue.
       - A hook may have changed or removed the stack. Skip if `item.id !in w.entities`; otherwise `var n =
         minOf(wanted, item.count)`.
       - Unless `carriedSource`, cap to the carry limit: `val free = w.rules.carryLimit(RulesContext(w, actor, item =
         item)) - actor.carriedWeight()`.
         - Stackable: `val fit = if (item.proto.weight <= 0) n else (free / item.proto.weight).coerceAtLeast(0)`.
         - Otherwise: `val fit = if (item.weight <= free) 1 else 0`.
         - If `fit < n`, set `capped = true` and `n = fit`. If `n <= 0`, continue. Always continue, never break: a later
           match may be lighter.
       - `maxLag = maxOf(maxLag, lag ?: 0)`.
       - `val moved = w.moveItem(item, ItemLocation.Carried(actor.id), n)`, then `val desc = moved.describe()`.
       - Send `GET_SELF_FROM` (with a container) or `GET_SELF`, and `GET_ROOM`, with `"item" to desc`.
       - Dispatch `ON_GET` with `moved`. If `moved.id in w.entities`, call `w.stack(moved)`.
     - If `capped`, tell `KernelMsg.CANT_CARRY`. Return `maxLag`.
  8. **Drop (`planDrop` and its action):**
     - Change the `drop` command's `help` string in the command table to end with `"Usage: drop <item|count item|all
       [item]>"`.
     - `planDrop`: `val spec = parseItemSpec(c.args) ?: return null.also { c.world.tell(c.actor, KernelMsg.NOT_CARRYING)
       }`. A blank argument gives `null` and therefore `NOT_CARRYING`, as today.
     - Delete the existing `veto()` override.
     - `validate`: if `matchItems(actor.inventory, spec)` is empty, return `w.msg(KernelMsg.NOT_CARRYING)`; else return
       `null`.
     - `resolve`: for each `(item, wanted)` in `matchItems(actor.inventory, spec)`:
       - Skip if `item.location != ItemLocation.Carried(actor.id)`.
       - Dispatch `BEFORE_DROP` on `item`; if it vetoes, `w.send(actor, veto)` and continue.
       - Skip if `item.id !in w.entities`; otherwise `val n = minOf(wanted, item.count)`.
       - `val moved = w.moveItem(item, ItemLocation.InRoom(actor.room.id, actor.trackIndex), n)` and `val desc =
         moved.describe()`.
       - Send `DROP_SELF` and `DROP_ROOM` with `"item" to desc`.
       - Dispatch `ON_DROP` with `moved`. If `moved.id in w.entities`, call `w.stack(moved)`.

     Then return `0`.

### Test & Documentation Requirements

Add these to `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`. Use `doLine`, which queues one line and runs
three pulses. The fixture has no ring or bag yet: add a non-stackable `ItemProto(ring, "a ring", listOf("ring"))` and an
open carried container such as `ItemProto(bag, "a bag", listOf("bag"), container = ContainerProto(capacity = 10,
maxWeight = 100))` (containers default to `OPEN`). Spawn a corpse with `f.w.spawnItem(Prototypes.CORPSE.label, ...)`;
every `Prototypes` includes it.

- `get 5 coins` from a ground stack of 10 leaves 5 on the ground and 5 in the inventory, and says "You get 5 gold
  coins.". `drop 3 coins` then leaves 2 carried and merges 3 into the ground stack (8).
- Two carried coin stacks that differ in a custom attribute (4 and 5): `drop 6 coins` drops all 4 of the first and 2 of
  the second, with two `DROP_SELF` lines ("You drop 4 gold coins." and "You drop 2 gold coins.").
- `get 2 ring` with three rings on the ground picks up two.
- `get 50000 coins` and `drop 50000 coins` with 10 coins move all 10, with no capped message.
- `get 50000 coins` when only 10 more weight units fit picks up 10 with one `before_get` and one `on_get`, then says
  "You get 10 gold coins." followed by "You can't carry that much.".
- `get all`, `get all coin`, `drop all`, and `drop all coin`.
- `get all` leaves a corpse on the ground and `drop all` keeps the bare `iron` key, while `get corpse` and `drop all
  iron` move them.
- Optional `from` and the container rule:
  - `get coin chest`, `get 5 coins chest`, `get all chest`, and `get all corpse` take from the container.
  - `get gold coin` takes from the ground.
  - `get coin chest` with the chest closed says the chest is closed.
- `get coin from bag` for a carried bag succeeds even when the actor is exactly at the carry limit.
- A `before_get` hook that vetoes is called once per stack, not twice.
- `scavenge` still makes a mob pick up an item.

In `docs/CHANGELOG.md`, record the `<what>` syntax, optional `from`, `get all <container>`, carry capping, and that
`before_get` / `before_drop` now fire once per source stack.

### Exit Conditions

- [] `./gradlew markdownFormat && ./gradlew check` (with `BypassSandbox: true`) exits `0` with zero warnings.
- [] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns `0` matches.
- [] `grep -n "override fun veto" core/src/main/kotlin/gups/core/world/KernelCommands.kt` shows no `veto` for get or
  drop.
- [] All new and existing `ItemCommandsTest` cases pass.

---

## Phase 4: `give` and `put` with Quantities, `all`, Optional Prepositions, and Finalization

### Objective & Scope

- **In Scope:**
  - Rewrite `planGive` and `planPut` and their actions to use `ItemSpec` and `matchItems`, to resolve when they run, and
    to support `<count>`, `all`, `all <keyword>`, an optional `to`, and an optional `in` / `into`.
  - Cap `give` to the receiver's remaining `carry_limit` (sending `GIVE_FULL` when capped). Cap `put` to the container's
    free `capacity` and `max_weight` (sending `PUT_WONT_FIT` or `PUT_FULL` when capped).
  - `put` never moves the target container itself.
  - Mark ``### Quantities and `all` `` in `docs/DESIGN_NEEDED.md` as completed, without deleting the entry.
  - Add the **M16** row to `docs/SPEC.md` `§7.4`, and update `§4.9` and `docs/CHANGELOG.md`.
- **Out of Scope:**
  - Deleting the `DESIGN_NEEDED.md` entry, which waits for maintainer review.
  - Adding `before_give`, `before_put` or `on_put` hooks.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
  - `core/src/main/kotlin/gups/core/world/Items.kt` (`World.stack` merge rules)
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

Update `docs/SPEC.md`:

- **`§4.9` ("Item and combat command details"):**
  - Replace the `give` bullet with: "**give <what> [to] <actor>:** gives one item, a count, or all matching items from
    the inventory. A count is capped to what is there and to what the receiver can carry ("<Target> can't carry that
    much." when capped). Fires `on_give` once per stack moved."
  - Replace the `put` bullet with: "**put <what> [in|into] <container>:** the container is carried, or on the ground
    within `get_lag` reach, and open. Moves one item, a count, or all matching items (never the container itself). A
    count is capped to the container's free item capacity and `max_weight`."
- **`§7.4` (Implementation Milestones):** add the row `| **M16** | Item quantities, pluralization, and all:
  item(plural=), phrase keywords, stack count display, no_all tag, count on move_item/give/destroy (replacing split),
  and quantities, all, optional prepositions, and carry/capacity capping on get, drop, give, and put (§3.3, §4.2, §4.5,
  §4.9). |`

Update `docs/DESIGN_NEEDED.md`: rename the heading ``### Quantities and `all` `` to ``### Quantities and `all`
(COMPLETED — M16) ``, and keep the entry text intact.

### Implementation Blueprint

- **Algorithmic Steps & Invariants (`KernelCommands.kt`):**
  1. **`planGive(c)`:**
     - Change the `give` command's `help` string in the command table near the top of `KernelCommands` to end with
       `"Usage: give <item|count item|all [item]> [to] <target>"`, and update the KDoc above `planGive` to match.
     - If `Regex("\\s+to\\s+", RegexOption.IGNORE_CASE).findAll(c.args).lastOrNull()` matches, `whatStr` is the text
       before it and `targetStr` the text after.
     - Otherwise, split `c.args.trim()` on whitespace. With fewer than 2 words, tell `KernelMsg.GIVE_USAGE` and return
       `null`. `targetStr` is the last word, and `whatStr` is the words before it joined by `" "`.
     - `val spec = parseItemSpec(whatStr) ?: return null.also { c.world.tell(c.actor, KernelMsg.GIVE_USAGE) }`.
     - Return `GiveAction(spec, targetStr.trim())`, a new `private class` next to `GetAction`. It has no `veto()`
       override.
  2. **`GiveAction.validate(w, actor)`:**
     - `val target = w.findActor(actor, targetStr) ?: return w.msg(KernelMsg.NOT_HERE)` (`findActor` already checks
       presence).
     - If `target === actor`, return `w.msg(KernelMsg.GIVE_ALREADY)`.
     - `val matches = matchItems(actor.inventory, spec)`. If it is empty, return `w.msg(KernelMsg.NOT_CARRYING)`.
     - If no matched item can fit 1 unit within the target's `carry_limit` (unit weight as in Phase 3), return
       `w.msg(KernelMsg.GIVE_FULL, "target" to target.name)`.
     - Return `null`.
  3. **`GiveAction.resolve(w, actor)`:**
     - `val target = w.findActor(actor, targetStr) ?: return 0`. `var capped = false`.
     - For each `(item, wanted)` in `matchItems(actor.inventory, spec)`:
       - Skip if `item.location != ItemLocation.Carried(actor.id)`.
       - `var n = minOf(wanted, item.count)`, then cap to the target's remaining carry limit exactly as in Phase 3 step
         7 (always `continue` when 0 fit).
       - `val moved = w.moveItem(item, ItemLocation.Carried(target.id), n)` and `val desc = moved.describe()`.
       - Send `GIVE_SELF`, `GIVE_TARGET` and `GIVE_ROOM` with `"item" to desc`, exactly as the current give action does:
         `GIVE_ROOM` goes through `tellSight(..., actor, target)`, so neither the giver nor the receiver gets the room
         line.
       - Dispatch `ON_GIVE` with `moved` and `target`. If `moved.id in w.entities`, call `w.stack(moved)`.
     - If `capped`, tell `GIVE_FULL` with `"target" to target.name`. Return `0`.
  4. **`planPut(c)`:**
     - Change the `put` command's `help` string in the command table to end with `"Usage: put <item|count item|all
       [item]> [in|into] <container>"`, and update the KDoc above `planPut` to match.
     - If `Regex("\\s+(in|into)\\s+", RegexOption.IGNORE_CASE).findAll(c.args).lastOrNull()` matches, split there into
       `whatStr` and `boxStr`.
     - Otherwise, split on whitespace. With fewer than 2 words, tell `KernelMsg.PUT_USAGE` and return `null`. `boxStr`
       is the last word, and `whatStr` is the words before it.
     - `val spec = parseItemSpec(whatStr) ?: return null.also { c.world.tell(c.actor, KernelMsg.PUT_USAGE) }`.
     - Return `PutAction(spec, boxStr.trim())`, a new `private class`.
  5. **Merge check helper:** `private fun World.mergesInto(item: Item, box: Item): Boolean`. It is true when `stack`
     would merge `item` into an existing stack in `box`, so the item takes no new slot. Use the same conditions as
     `World.stack` in `Items.kt`: `item.proto.stackable`, no timers and no `nameOverride` on either item, and some
     `box.contents` entry with the same `protoLabel`, `decayAt` and `customAttributes`.
  6. **`PutAction.validate(w, actor)`:**
     - `val box = w.findItem(actor, boxStr, inventory = true, ground = true) ?: return w.msg(KernelMsg.DONT_SEE)`.
     - `val matches = matchItems(actor.inventory, spec, exclude = box)`. If it is empty:
       - If `!spec.all`, `box` is in `actor.inventory`, and `spec.keyword != null && box.matches(spec.keyword)`, return
         `w.msg(KernelMsg.PUT_ITSELF)`. This keeps `put chest in chest` saying "You can't put something inside itself."
       - Otherwise return `w.msg(KernelMsg.NOT_CARRYING)`.
     - `val state = box.container ?: return w.msg(KernelMsg.NOT_CONTAINER, "item" to box.name)`. Then check `isPresent`
       (`DONT_SEE`), `w.reach(actor, box)?.let { return it }`, and that it is `OPEN` (`PUT_CLOSED`), in the existing
       order.
     - If no match has a free slot (`box.contents.size < state.capacity || w.mergesInto(i, box)`), return
       `w.msg(KernelMsg.PUT_FULL, "container" to box.name)`. `PUT_FULL` has a `{container}` placeholder.
     - If no match fits 1 unit by weight (`box.contents.sumOf { it.weight } + unitWeight <= state.maxWeight`), return
       `PUT_WONT_FIT` with `"item" to matches.first().first.name` and `"container" to box.name`.
     - Return `null`.
  7. **`PutAction.resolve(w, actor)`:**
     - `val box = w.findItem(actor, boxStr, inventory = true, ground = true) ?: return 0`. `val state = box.container ?:
       return 0`. `var full = false` and `var tooHeavy: String? = null`.
     - For each `(item, wanted)` in `matchItems(actor.inventory, spec, exclude = box)`:
       - Skip if `item.location != ItemLocation.Carried(actor.id)`.
       - If `box.contents.size >= state.capacity && !w.mergesInto(item, box)`, set `full = true` and continue.
       - `var n = minOf(wanted, item.count)`. Cap by `val free = state.maxWeight - box.contents.sumOf { it.weight }`,
         using the same arithmetic as Phase 3 step 7. When the cap reduces `n`, set `tooHeavy = tooHeavy ?: item.name`;
         when `n <= 0`, continue.
       - `val moved = w.moveItem(item, ItemLocation.Contained(box.id), n)` and `val desc = moved.describe()`.
       - Send `PUT_SELF` and `PUT_ROOM` with `"item" to desc` and `"container" to box.name`.
       - `w.stack(moved)`.
     - After the loop: if `tooHeavy != null`, tell `PUT_WONT_FIT` with `"item" to tooHeavy` and `"container" to
       box.name`; else, if `full`, tell `PUT_FULL` with `"container" to box.name`. Return `0`.

### Test & Documentation Requirements

Add these to `core/src/test/kotlin/gups/core/world/ItemCommandsTest.kt`:

- `give 5 coins to bob` and `give 5 coins bob` each split the stack, say "You give 5 gold coins to Bob.", and merge into
  Bob's existing coin stack.
- `give all bob` hands over everything except the bare `iron` key, and `give all coins bob` hands over only coins.
- `give 50000 coins bob` gives as many as Bob can carry, then says "Bob can't carry that much.".
- `put 3 coins in chest`, `put 3 coins into chest`, and `put 3 coins chest` all work.
- `put all in chest` and `put all chest` never move the chest itself, and skip `no_all` items.
- The fixture chest has `capacity = 2` and `maxWeight = 5` and starts locked. Open it with `box.container!!.state =
  DoorState.OPEN` rather than carrying `T.KEY`: the brass key has `kind = "misc"`, so it is not key-only and bare `all`
  would put it in the chest and throw off the counts.
  - `put 50000 coins chest` moves 5 coins and says "A gold coin won't fit in a chest.".
  - `put all chest` with three different light items moves two and says "A chest is full.".
- `put chest in chest` still says "You can't put something inside itself.".
- Late resolution: queue `get all chest`, `drop 2 coins` and `put all chest` with `input(p, ...)` three times, then call
  `run(...)` once, so all three are queued before the first resolves. Each command acts on the state the previous one
  left. (There is no `;` separator; each line is its own input.)

In `docs/CHANGELOG.md`, record the `give` and `put` syntax, the capping, and the new usage strings.

### Exit Conditions

- [] `docs/DESIGN_NEEDED.md` contains ``### Quantities and `all` (COMPLETED — M16)``.
- [] `docs/SPEC.md` `§7.4` contains the **M16** row.
- [] `grep -rnE "§|\bD-[0-9]{3}\b|(//|\*).*\bSPEC\b" */src/main/` returns `0` matches.
- [] `./gradlew markdownFormat && ./gradlew check` (with `BypassSandbox: true`) exits `0` with zero warnings and all
  tests passing.
