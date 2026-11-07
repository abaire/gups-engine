# Milestone 40: Groups and Levels in `who`, `score` and the Web Client

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### Showing groups in `who`, `score` and clients` (under
  "Social and communication").
- **Design Summary:**
  1. **`who` lines gain two slots.** The kernel entries `who.player` and `who.linkdead` get optional `{level}` and
     `{group}` placeholders. Each slot is filled with a *fragment*, itself a catalog entry, or with `""`:
     - `{level}` is the `leveling` entry `leveling.who_level` (`"[{level}] "`) when the `leveling` feature is on, else
       `""`. This also fixes a gap: §3.10 already says `leveling` puts the level in `who`, but nothing did.
     - `{group}` is the kernel entry `who.group` (`" [{leader}'s group]"`) when the listed player is in a group, else
       `""`.
     - Neither fragment has required placeholders, so a world can set either to `""` in `MESSAGES.gups` to hide levels
       or group membership from `who`. That is how a world chooses privacy; the engine fixes no policy.
     - New defaults: `who.player` is `{level}{name}{group}` and `who.linkdead` is `{level}{name}{group} (link-dead)`,
       each indented two spaces as today (the Kotlin block in Phase 1 is exact). `name` stays required. An existing
       override that leaves out the new slots stays valid and simply shows neither.
  2. **`score` gains one line while grouped.** The kernel entry `score.group` = `"Group: {leader}'s, {count} members"`
     (placeholders `leader`, `count`, none required). `{leader}` is the leader's name, `{count}` the number of player
     members, the character included. It is sent only while the character is in a group, after the leveling lines and
     before the creation choices.
  3. **GMCP is unchanged.** `Char.Status` does not carry a group id: `Char.Group` already has `id`, and copying it would
     make `Char.Status` resend on every group change.
  4. **The bundled web client shows the group.**
     - A **group panel** in the side panel, right after the vitals, hidden while `Char.Group` is `null`. One row per
       member in payload order (leader first): the name, a `★` for the leader, `L<level>` when `level` is present, and
       compact vital bars drawn from `Char.Schema` like the main vitals. Members with `in_room: false` are dimmed.
     - A **band marker**: actor entries in the band view whose name matches a group member's name get a `group` CSS
       class. Player names are unique, so matching by name needs no spec change. (Adding member ids to `Char.Group` was
       considered and is out of scope.)
- **Specification Impact:** §4.9 (new `who` bullet; `score` bullet), §4.10 (`Char.Group` payload bullet), §4.18 (catalog
  entries), §7.4 (M40 row).
- **Decision Record Updates:** None.

---

## Phase 1: Levels and Groups in `who` and `score`

### Objective & Scope

- **In Scope:**
  - The four catalog changes: new `who.group`, `score.group`, `leveling.who_level`; changed defaults and placeholders of
    `who.player` and `who.linkdead`.
  - `who` and `score` filling them in `KernelCommands.kt`.
  - Kotlin tests, the spec text for §4.9 and §4.18, and a CHANGELOG entry.
- **Out of Scope (do not attempt these; later phases or other milestones own them):**
  - Any web client file (Phase 2).
  - Any change to GMCP payloads, including `Char.Status` and `Char.Group`.
  - The staff `@who` tool in `core/src/main/kotlin/gups/core/world/Tools.kt` (it is not catalog output).
  - Sorting or filtering the `who` list, showing pets in `who`, or a `who` that differs by viewer.
  - §7.4 and `docs/DESIGN_NEEDED.md` (Phase 3).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Messages.kt` (whole file: `MsgGroup.msg`, `World.msg`, `World.tell`,
    `Messages.problems`, `Messages.fill`).
  - `core/src/main/kotlin/gups/core/world/Groups.kt` (`groupOf`, `groupMembers`, `groupAdd`).
  - `core/src/test/kotlin/gups/core/world/GroupTest.kt` (fixture setup at the top; `groupAdd` usage in the first test).
  - `core/src/test/kotlin/gups/core/world/LevelingTest.kt` around line 704 (the `score` ready-line test: the `f.input(p,
    "score"); f.run(4); f.out(p)` pattern).
  - `core/src/test/kotlin/gups/core/world/FeaturesOffTest.kt` (`Fixture(features = without(Feature.X))`).
  - `docs/SPEC.md` §4.9 (the `score` bullet, near line 1864) and §4.18 (the "Leveling entries include" bullet, near line
    2880).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`
  - `core/src/main/kotlin/gups/core/world/Leveling.kt` (the `LevelingMsg` object only)
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt` (`score` at ~line 428, `who` at ~line 524)
  - `core/src/test/kotlin/gups/core/world/GroupTest.kt`, `LevelingTest.kt`, `FeaturesOffTest.kt` (add tests)
  - `docs/SPEC.md` (§4.9, §4.18), `docs/CHANGELOG.md`
  - Golden files that list the catalog, regenerated rather than hand-edited: `cli/src/test/resources/api.golden.json`
    and `cli/src/test/resources/docs-golden/` (`api.json`, `world.html`). After the code change, run `./gradlew
    :cli:test --tests '*CommandsTest*' --tests '*DocsRendererTest*' -Dgups.updateGolden=true`, then review the diff:
    only the catalog entries in this phase may change.
- **Do Not Touch:** `Gmcp.kt`, `Groups.kt`, `Tools.kt`, `net/`, `webclient/`, `script/`, `plugin-api/`,
  `template-world/`.

### Specification & Decision Record Changes

- §4.9, after the `score` bullet, add a new bullet:

  > - **`who`** lists every player in the world, link-dead ones marked. Each line is `who.player` or `who.linkdead` with
  >   two optional slots, each filled by a catalog fragment or left empty: `{level}` is `leveling.who_level`
  >   (`"[{level}] "`) with the `leveling` feature, and `{group}` is `who.group` (`" [{leader}'s group]"`) for a player
  >   in a group (§3.3). A world hides levels or groups from `who` by setting the fragment to `""` (§4.18).

- §4.9, `score` bullet: after "its progression with the `leveling` feature," insert "its group while it is in one
  (`score.group`, `"Group: {leader}'s, {count} members"`, where `count` counts the players in the group, the character
  included),". Leave the rest of the bullet unchanged.

- §4.18, after the "Leveling entries include" sub-bullet, add `leveling.who_level` to that bullet (`"[{level}] "`,
  placeholder `level`, nothing required), and add a new sub-bullet:

  > - `who` and group entries include `who.player` (`{level}{name}{group}`, indented two spaces) and `who.linkdead`
  >   (`{level}{name}{group} (link-dead)`, indented two spaces), both with placeholders `level`, `name`, `group` and
  >   required `name`; `who.group` (a space, then `[{leader}'s group]`; placeholder `leader`); and `score.group`
  >   (`"Group: {leader}'s, {count} members"`, placeholders `leader`, `count`). Fragments that fill a slot have no
  >   required placeholders, so an override may empty them.

  No inline code span in SPEC.md may start with a space: `markdownFormat` collapses it. Describe leading whitespace in
  words, as above.

- `docs/CHANGELOG.md`, under `## api_version 1 (unreleased)`, add a new `### Groups and levels in who and score`
  section:

  - `who` shows each player's level (with `leveling`) and group leader: new entries `leveling.who_level` and
    `who.group`, and new `{level}` and `{group}` placeholders in `who.player` and `who.linkdead`. Override a fragment to
    `""` in `MESSAGES.gups` to hide it.
  - `score` shows `score.group` while the character is in a group.

### Implementation Blueprint

- **Signatures & Types** (exact catalog declarations, using the existing `msg(id, default, placeholders, required)`):

  ```kotlin
  // KernelMessages.kt, KernelMsg: replace WHO_PLAYER and WHO_LINKDEAD, add WHO_GROUP and SCORE_GROUP
  val WHO_PLAYER = msg("who.player", "  {level}{name}{group}", "level,name,group", "name")
  val WHO_LINKDEAD =
    msg("who.linkdead", "  {level}{name}{group} (link-dead)", "level,name,group", "name")
  val WHO_GROUP = msg("who.group", " [{leader}'s group]", "leader")
  val SCORE_GROUP = msg("score.group", "Group: {leader}'s, {count} members", "leader,count")

  // Leveling.kt, LevelingMsg
  val WHO_LEVEL = msg("leveling.who_level", "[{level}] ", "level")
  ```

  Put `SCORE_GROUP` next to the other `SCORE_*` entries and `WHO_GROUP` next to the other `WHO_*` entries.

- **Algorithmic Steps & Invariants:**

  1. **Tests first** (see below); run them and confirm they fail.
  2. `who` (`KernelCommands.kt` ~524): for each `p` in `w.players` (keep the current order), compute
     - `level = if (Feature.LEVELING in w.features) w.msg(LevelingMsg.WHO_LEVEL, "level" to p.level) else ""`
     - `group = w.groupOf(p)?.let { w.msg(KernelMsg.WHO_GROUP, "leader" to it.leader.name) } ?: ""`
     - then `w.msg(if (p.session == null) WHO_LINKDEAD else WHO_PLAYER, "level" to level, "name" to p.name, "group" to
       group)`.
     - Invariant: `leveling.who_level` is never filled when `leveling` is off (§4.18: an id of a feature that is off is
       never sent).
  3. `score` (`KernelCommands.kt` ~428): after the `Feature.LEVELING` block and before the creation-choices block, add:
     `w.groupOf(a)?.let { g -> lines += w.msg(KernelMsg.SCORE_GROUP, "leader" to g.leader.name, "count" to
     w.groupMembers(a).size) }`. Only for players: guard with `a is Player`, because `groupOf` resolves a pet to its
     owner's group and a pet's `score` should not claim a group.
  4. `Messages.fill` capitalizes `{name}` only at text position 0. The new defaults start with two spaces, so names are
     never capitalized; this is unchanged behavior.
  5. Update the KDoc above `score` to mention the group line.

### Test & Documentation Requirements

Add these tests, each written before the code:

- `GroupTest.kt`:
  - `who marks each grouped player with its leader and leaves ungrouped players plain`: Ann, Ben, Cal; `groupAdd(ben,
    ann)`; Cal types `who`. The output contains `Ann [Ann's group]`, `Ben [Ann's group]`, and a line ending in `Cal`
    with no `group` text after it. Assert on substrings that don't depend on the level prefix, because `GroupTest` runs
    once per fixture schema.
  - `score names the group and its size only while grouped`: Ann's `score` has no `Group:` line; after `groupAdd(ben,
    ann)` Ben's `score` contains `Group: Ann's, 2 members`; after `groupRemove(ben)` it has no `Group:` line again.
  - `a world that empties who.group hides groups from who`: build the fixture with `Fixture(messages = mapOf("who.group"
    to ""))` (the same parameter `MessagesTest.kt` line 56 uses). A grouped player's `who` line contains no `group`
    text.
- `LevelingTest.kt`: `who shows each player's level`. Set `p.level` on one player (or `levelUp`). `who` contains `[1]
  Ann` and `[2] Ben`, and a link-dead player's line ends `(link-dead)` with the level prefix.
- `FeaturesOffTest.kt`: `who shows no level without leveling`. With `Fixture(features = without(Feature.LEVELING))`, the
  `who` line is exactly ` Ann`.
- `MessagesTest.kt` needs no change if it validates every default against its placeholders automatically. Run it to
  confirm the new entries pass.

### Exit Conditions

- [] `./gradlew :core:test` passes, including the new tests (which failed before the change).
- [] `./gradlew check` passes with zero warnings (Kotlin warnings are errors; `markdownCheck` included after `./gradlew
  markdownFormat`).
- [] §4.9 has a `who` bullet, and its `score` bullet and §4.18 list the new entries exactly as above.

---

## Phase 2: Web Client Group Panel and Band Marker

### Objective & Scope

- **In Scope:**
  - Drawing `Char.Group` as a group panel.
  - Marking group members in the band view.
  - Clearing both on disconnect and logout.
  - The served-page test assertion and a hidden-panel check in the browser test.
  - The §4.10 note.
- **Out of Scope (do not attempt these; they belong to no phase of this milestone):**
  - Any change to `Char.Group` or another GMCP payload, including adding member ids.
  - Any engine (`core/`) change.
  - Group commands or buttons in the client (invite, leave). Players type them.
  - A JS test framework or Node tooling. The client deliberately has no build step and no Node.

### Context Working Set

- **Read / Reference Only:**
  - `webclient/build.gradle.kts` (no build step; files ship as resources).
  - `docs/SPEC.md` §4.10, the `Char.Group` row of the package table and the `Char.Group` bullet under "GMCP payloads"
    (near line 2236).
  - `cli/src/test/kotlin/gups/cli/WebClientTest.kt`: the frame-level test (~line 128, `assertContains(index,
    "skills-panel")`) and the browser test (~line 214).
- **Files to Modify / Create:**
  - `webclient/src/main/resources/gups/webclient/index.html`
  - `webclient/src/main/resources/gups/webclient/panels.js`
  - `webclient/src/main/resources/gups/webclient/app.js`
  - `webclient/src/main/resources/gups/webclient/style.css`
  - `cli/src/test/kotlin/gups/cli/WebClientTest.kt`
  - `docs/SPEC.md` (§4.10 only)
- **Do Not Touch:** `core/`, `net/`, `webclient/src/main/resources/gups/webclient/markup.js`, `files.txt` (no new file
  is added).

### Specification & Decision Record Changes

- §4.10, "GMCP payloads", end of the `Char.Group` bullet, append: "The bundled web client draws it as a group panel
  (each member's vitals bars, the leader marked, members elsewhere dimmed) and marks group members in its band view."

### Implementation Blueprint

- **`index.html`:** directly after `<div id="vitals" class="vitals"></div>`, add:

  ```html
  <div id="group-panel" class="group-panel" hidden>
    <h2>Group</h2>
    <ul id="group-list" class="group-list"></ul>
  </div>
  ```
- **`panels.js` signatures & types:**

  ```js
  /**
   * @typedef {{name: string, leader: boolean, in_room: boolean, level?: number, vitals: Vitals}} GroupMember
   * @typedef {{id: number, leader: string, members: GroupMember[]}} GroupData
   */

  /** One vital bar; `mini` bars (the group panel's) carry no data attributes and no number label. */
  function vitalBar(name, label, index, now, max, mini) { ... }

  export function drawVitals(box, schema, v) { ... }            // now builds each bar with vitalBar(..., false)

  /**
   * The group panel: one row per member, leader first, hidden with no group.
   * @param {HTMLElement} panel
   * @param {HTMLElement} list
   * @param {Schema} schema
   * @param {GroupData|null} data
   */
  export function drawGroup(panel, list, schema, data) { ... }

  /** @param {Set<string>} [groupNames] names of group members, marked with the `group` class */
  export function drawBands(box, bands, groupNames = new Set()) { ... }
  ```
- **Algorithmic Steps & Invariants:**
  1. **`vitalBar`:** move the body of the `drawVitals` loop into `vitalBar`. The non-mini output must be byte-for-byte
     the same DOM as today: class `bar tone<i % TONES>`, `data-vital`, `data-value`, a `.label` with two spans, and
     `.track > .fill`. The browser test selects `#vitals .bar .label span:first-child` and `.bar[data-vital='hp']`.
     - Mini bars use class `bar mini tone<i % TONES>`. They set no `data-vital` or `data-value`, so the global
       `.bar[data-vital='hp']` selector still matches only the main vitals.
     - A mini bar's `.label` has one span with the vital label. Its `title` is `${now}/${max}`.
  2. **`drawGroup`:**
     - If `data` is null or has no members: `panel.hidden = true; list.replaceChildren(); return`.
     - Otherwise unhide the panel. For each member, build an `li` with class `member`, plus `away` when `!m.in_room`. It
       holds:
       - a `.member-name` line with the name, `" ★"` when `m.leader`, and `` ` L${m.level}` `` when `level` is a number;
       - one mini bar per `schema.vitals` entry, in schema order, from `m.vitals`.
     - Members appear in payload order.
  3. **`drawBands`:** unchanged, except an actor entry (`e.kind === "actor"`) whose `e.name` is in `groupNames` gets
     class `who actor group`.
  4. **`app.js` state & wiring:**
     - Add `let lastBands = null; let lastGroup = null;` next to `lastVitals`.
     - Add a helper `groupNames()`, which returns `new Set((lastGroup?.members ?? []).map((m) => m.name))`.
     - `"Room.Bands"`: `lastBands = data; drawBands($("bands"), data, groupNames());`.
     - New case `"Char.Group"`:
       - `lastGroup = data; drawGroup($("group-panel"), $("group-list"), schema, data);`
       - then, if `lastBands`, redraw the bands with `groupNames()`.
       - The two packages arrive in either order, so each draw uses the latest of the other.
     - `"Char.Schema"`: after the existing redraw, also redraw the group panel when `lastGroup` is set, because its bars
       depend on the schema.
     - Import `drawGroup`.
     - In the WebSocket `close` handler and the logout handler, next to the existing `drawCreation(..., null, ...)`
       calls:
       - reset `lastGroup = null; lastBands = null;`
       - call `drawGroup($("group-panel"), $("group-list"), schema, null)`.
  5. **`style.css`**, next to `.skills-panel`:
     - `.group-panel { margin-top: 1rem; }`
     - `.group-list { list-style: none; margin: 0; padding: 0; }`
     - `.group-list .member { margin-bottom: 0.5rem; }`
     - `.group-list .member.away { opacity: 0.5; }`
     - `.member-name { font-size: 0.85rem; }`
     - `.bar.mini { margin-bottom: 0.15rem; }`
     - `.bar.mini .label { font-size: 0.7rem; }`
     - `.bar.mini .track { height: 0.3rem; }`
     - `.band .who.group { color: var(--accent); }`
     - Use only variables already defined at `:root` (`--accent`, `--muted`, `--bg`).

### Test & Documentation Requirements

- `WebClientTest.kt`, frame-level test: add `assertContains(index, "group-panel")` next to the `skills-panel` assertion.
  This runs everywhere.
- `WebClientTest.kt`, browser test: after the `room-name` assertion, add
  `assertFalse(driver.findElement(By.id("group-panel")).isDisplayed)`, since a lone player has no group. Import
  `kotlin.test.assertFalse` if needed. This runs only on Linux CI or with `-Dgups.browserTest=true` and Firefox. Do not
  make it a local exit condition.
- JSDoc on every new or changed function as shown above. Update the top comment of `panels.js` to "vitals bars, group,
  affects and bands."
- Optional manual check if the agent can run a dev server:
  1. Start `gups serve --dev` against `template-world`.
  2. Log in two players and group them with `invite` and `accept`.
  3. Confirm that the panel appears, the leader shows ★, the bars move with damage, a member in another room is dimmed,
     and the partner's band entry is highlighted.

### Exit Conditions

- [] `./gradlew :cli:test` passes (the frame-level test finds `group-panel` and all four assets are served).
- [] `./gradlew check` passes with zero warnings (`markdownCheck` included after `./gradlew markdownFormat`).
- [] `drawVitals` produces the same DOM as before for `#vitals`. Check by comparing the bar-building code before and
  after the change.
- [] §4.10's `Char.Group` bullet carries the web client sentence.

---

## Phase 3: Finalization & Backlog Update

### Objective & Scope

- **In Scope:** §7.4 milestone row, `DESIGN_NEEDED` bookkeeping, full verification.
- **Out of Scope (do not attempt):** any code change except fixing a failure found by the checks below; deleting the
  `DESIGN_NEEDED` entry.

### Context Working Set

- **Files to Modify:** `docs/SPEC.md` (§7.4 only), `docs/DESIGN_NEEDED.md`.
- **Do Not Touch:** everything else.

### Specification & Decision Record Changes

- §7.4, add a row after the M39 row if present, otherwise after the M38 row: `| **M40** | Levels and groups in `who`
  (the `leveling.who_level` and `who.group` fragments), the `score.group` line, and the web client's group panel and
  band marker. |`
- `docs/DESIGN_NEEDED.md`, `### Showing groups in `who`, `score` and clients`:
  - Append ` (completed in M40)` to the heading.
  - Add a first line under it: "**Completed:** designed and built in [MILESTONE_40.md](MILESTONE_40.md)."
  - Do not delete the entry.

### Exit Conditions

- [] `./gradlew markdownFormat` then `./gradlew check` passes with zero warnings or errors.
- [] A `gups scenario` run per `AGENTS.md` (in `local-world-test/`, against `template-world`, package
  `//zones/tutorial`):
  - Tess and Ash spawn in one room. Ash types `invite tess`, Tess types `accept`, then both type `who` and `score`.
  - The transcript shows `[1] Tess [Ash's group]` and `[1] Ash [Ash's group]` in `who`, and `Group: Ash's, 2 members` in
    both `score` outputs.
  - Delete `local-world-test/` afterwards.
- [] The `DESIGN_NEEDED` entry is marked completed, not deleted, and §4.9, §4.10, §4.18 and §7.4 match the
  implementation.
