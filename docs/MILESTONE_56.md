# Milestone 56: Mudlet Automapper Support

**Prerequisites:** [M55](MILESTONE_55.md) must be finished first (it is: `core/.../world/Destroy.kt` exists). Where line
numbers below are wrong, find the symbol by name.

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** [DESIGN_NEEDED.md](DESIGN_NEEDED.md): `## Networking and clients` → `### Mudlet automapper
  support`. (Its spec pointers §3.1 and §5.10 are stale; the real sections are §2.4 and §4.10.)

- **Design Summary:**

  - **Mechanism.** The engine ships a small Mudlet mapper script, `mapper.lua`, packaged as `gups-mapper.mpackage` and
    served over HTTP. The server offers it to clients with the GMCP message `Client.GUI {"version", "url"}`. Mudlet
    installs it, or replaces an older version, without asking (unless the player turned that preference off). Mudlet's
    built-in generic mapper reads room text, not GMCP, so a script of our own is the only way to fill a fresh profile's
    map.
  - **`Room.Info` is reshaped.** Only GMCP reads it: the bundled web client uses only `name` and `label`, and no engine
    code, plugin view or `ctx` field reads the payload. No `api_version` bump.

    ```json
    {"num": 1687381018, "label": "//zones/tutorial:gate", "name": "The Gate",
    "area": "//zones/tutorial", "area_name": "Tutorial",
    "exits": {"n": 81724, "d": 5521, "portal": 90211},
    "doors": {"n": "closed"},
    "regions": ["outdoors"]}
    ```

    - `num`: the room's map number (below).
    - `area`: `//` plus the room's package path (`label.pkg`). `area_name`: that package's zone `title`, or the package
      path when there is no zone prototype.
    - `exits`: every exit, keyed by direction. The ten standard directions use their short aliases (`n`, `ne`, `e`,
      `se`, `s`, `sw`, `w`, `nw`, `u`, `d`); any other exit uses its own name. The value is the destination's `num`, or
      `null` when that room isn't loaded.
    - `doors`: only the exits that have a door, under the same keys, with `"open"`, `"closed"` or `"locked"`.
    - `regions`: unchanged (sorted).
    - **Nothing is masked.** `look` already lists every exit; a destination `num` is an opaque number.
    - **No `environment` and no coordinates** are sent.
  - **Room map numbers.** `num` is FNV-1a 32-bit over the UTF-8 bytes of `label.v`, with the top bit cleared (`and
    0x7FFFFFFF`); a result of 0 becomes 1. Rooms are numbered in ascending label order. A room whose number is already
    taken tries `label.v + "#1"`, `"#2"`, … until it gets a free one. Collisions are never an error. The table is
    computed once per `Prototypes` (so every load and reload rebuilds it) and is never stored.
  - **`Client.GUI`.**
    - Payload: `{"version": <package version>, "url": "<base>/mudlet/gups-mapper.mpackage"}`.
    - It is sent once each time a session takes a character (in `World.attach`, where the GMCP cache already resets).
    - It goes to every session with GMCP on, whatever `Core.Supports` lists. That is the one written exception to the
      delivery rule.
    - It is sent only when the server knows a base URL. It is an event: never cached.
    - `"Client.GUI"` joins `ENGINE_GMCP_PACKAGES`, so plugins and scripts can't declare it.
  - **Base URL.**
    - In `--prod`, it comes from an optional `--public-url URL` flag or `server(public_url = ...)`. It must be an
      absolute `http` or `https` URL and needs `--https`.
    - Without it, `--prod` sends no `Client.GUI` and, when `--https` is on, logs one warning at startup.
    - In `--dev`, the base is `http://127.0.0.1:<bound docs port>`, automatic. `--public-url` is `--prod` only.
  - **The package.**
    - Source: `webclient/src/main/resources/gups/mudlet/mapper.lua`.
    - `net`'s new `MudletPackage` builds, in memory at startup, a zip with exactly two entries, in this order:
      `config.lua` and `gups-mapper.xml`. The XML is the script, text-escaped, inside Mudlet's XML package format.
      Entries have fixed timestamps.
    - Version: the first 16 hex digits of a SHA-256 over the script's bytes.
    - Served at `GET /mudlet/gups-mapper.mpackage` by both `WebServer` (`--prod`) and `DocsServer` (`--dev`), with no
      login, `Cache-Control: no-cache` and `Content-Type: application/zip`.
  - **The script** (written in the Lua 5.1/5.2 common subset; Mudlet runs 5.1, the tests run LuaJ's 5.2):
    - **Rooms:**
      - A Mudlet room id is `num`, and the room keeps its label in room user data `gups_label`.
      - Rooms elsewhere with the same label are deleted. A room at `num` whose label differs is replaced.
    - **Areas:**
      - Areas are keyed by `area`, stored in area user data `gups_area`, and named `area_name`.
      - When another area already has that name, ` (<area>)` is appended.
      - A renamed title renames the area.
    - **Layout:**
      - A new room reached by a standard direction from the previous room, in the same area, is placed one step from it
        in that direction.
      - Any other new room goes to the area's origin, or the nearest free spot. Overlaps are allowed.
      - A placed room is never moved.
    - **Each visit:**
      - Sets the room's name.
      - Links every exit whose destination is mapped and gives unmapped standard exits a stub.
      - Adds special exits for custom names and marks doors.
      - Removes exits and doors that are gone, and centers the map.
    - **Click-to-walk:**
      - `doSpeedWalk()` walks one step per `Room.Info`.
      - Arriving anywhere other than the next room on the path stops with `Walk interrupted.`
      - Any command the player sends stops it quietly.
  - **Tests.** A test-only dependency on LuaJ runs the script against a Lua stand-in for Mudlet's API.
  - **Docs.**
    - A "Playing with Mudlet" section in `template-world/README.md`.
    - `public_url` in the engine `README.md`'s production example and in §2.4's `server.gups` example.
- **Specification Impact:**
  - §2.2: the `webclient/` line in the module tree, and the class-loader sentence in the module rules.
  - §2.4: the `--dev` profile row, the `server(...)` example, and a new **Public URL** bullet in the flag list.
  - §4.10:
    - The GMCP packages table: the `Room.Info` row and a new `Client.GUI` row.
    - The **GMCP delivery** bullets: the exception and the events list.
    - The **GMCP payloads** `Room.Info` bullet, a new **Room map numbers** bullet and a new `Client.GUI` bullet.
    - The HTTPS endpoint table, and a new **Mudlet mapper** bullet after **Web client**.
  - §7.1: a new **Mudlet mapper** test bullet.
- **Decision Record Updates:** None.

### Conventions for every phase

1. Write the tests first, run them and watch them fail, then implement.
2. Comments explain *why*, never cite spec sections or decision IDs. All Kotlin warnings are errors.
3. Gradle commands run outside the sandbox. After any Markdown edit, run `./gradlew markdownFormat` and check that code
   blocks kept their indentation.
4. In every phase, regenerate the API files before the phase's final `./gradlew check` (an empty diff is fine):
   - `./gradlew :cli:test -Dgups.updateGolden=true` rewrites `cli/src/test/resources/api.golden.json` and
     `cli/src/test/resources/docs-golden/`.
   - `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups docs --update-baseline` rewrites
     `docs/api/api-1.json`.
   - Read the diff: only what the phase names may change.
5. CHANGELOG: Phase 1 adds `### Mudlet automapper` at the top of `## api_version 1 (unreleased)` in `docs/CHANGELOG.md`,
   above `### A hook when an item is destroyed`. Each later phase appends its bullets. Don't bump `api_version`.
6. Never read or touch `local-world/`. Scratch files go in `local-world-test/`, which you delete when done.
7. Messages given in quotes below are exact: tests assert them.
8. After changing code, run CodeScene `code_health_review` on the changed files and fix any drop.
9. **Spec text below is approved wording.** Apply it exactly, then run `markdownFormat` (it re-pads tables).

---

## Phase 1: Room map numbers and the reshaped `Room.Info`

### Objective & Scope

- **In Scope:**
  - The room numbering function and `Prototypes.roomNumbers`.
  - The new `Room.Info` payload (`num`, `area`, `area_name`, short-key `exits` to `num`, `doors`).
  - The web client's JSDoc typedef.
  - Spec and CHANGELOG text for both.
- **Out of Scope:** *Do not implement these; later phases do.* Anything Lua, `MudletPackage`, HTTP routes, `Client.GUI`,
  `public_url`, or any change to GMCP delivery rules.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/Ids.kt` (~22): `Label`, `label.pkg` (no leading `//`).
  - `core/src/main/kotlin/gups/core/model/Spatial.kt` (~66): `Direction`, `Direction.STANDARD`, `Direction.ALIASES`
    (short → long).
  - `core/src/main/kotlin/gups/core/model/Protos.kt`: `ZoneProto` (~478, `title` defaults to `pkg`) and `Prototypes`
    (~493, `zones`, `rooms`).
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (~88): `Exit` (`direction`, `to`, `door`).
- **Files to Modify / Create:**
  - create `core/src/main/kotlin/gups/core/model/RoomNumbers.kt`
  - `core/src/main/kotlin/gups/core/model/Protos.kt` (one `lazy` property on `Prototypes`)
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt` (`roomInfoPayload` ~210, which becomes a `World.` extension)
  - create `core/src/test/kotlin/gups/core/model/RoomNumbersTest.kt`
  - `core/src/test/kotlin/gups/core/world/GmcpTest.kt` (the `Room.Info` assertion ~78)
  - `webclient/src/main/resources/gups/webclient/panels.js` (the `RoomInfo` typedef at ~12 only)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `InfoCommands.kt` (`look`'s exit line stays full names), `Telnet.kt`, `WebServer.kt`, `app.js`, any
  GMCP delivery code (`wantsGmcp`, `supportsGmcp`, `STATE_PACKAGES`).

### Specification & Decision Record Changes

- §4.10 GMCP packages table, `Room.Info` row: replace the payload cell `` `{"label", "name", "exits": {dir:
  "open"/"closed"/"locked"/null}, "regions"}` `` with `` `{"num", "label", "name", "area", "area_name", "exits": {dir:
  num or null}, "doors": {dir: "open"/"closed"/"locked"}, "regions"}` ``. The "Sent" cell is unchanged.
- §4.10 **GMCP payloads**, replace the `Room.Info` bullet ("`Room.Info`: `exits` maps every exit's direction to its door
  state, or null when it has no door; `regions` are sorted.") with:

  > - `Room.Info`: `num` is the room's map number (below). `area` is `//` and the room's package path, and `area_name`
  >   is that package's zone `title` (the package path when it has none). `exits` maps every exit to its destination's
  >   `num`, or null when that room isn't loaded; the ten standard directions are keyed by their short names (`n`, `ne`,
  >   `e`, `se`, `s`, `sw`, `w`, `nw`, `u`, `d`) and other exits by their own. `doors` maps each exit that has a door,
  >   under the same key, to `"open"`, `"closed"` or `"locked"`. `regions` are sorted. Nothing is hidden: every exit
  >   `look` lists is here, with its destination.
  > - **Room map numbers:** a room's `num` is FNV-1a (32-bit) over the UTF-8 bytes of its label, with the top bit
  >   cleared; a result of 0 becomes 1. Rooms are numbered in ascending label order, and a room whose number is taken
  >   tries its label followed by `#1`, `#2`, … until one is free. The numbers are recomputed whenever prototypes load
  >   or reload and are never stored, so a room keeps its number across restarts unless a newly added room collides with
  >   it and sorts first.

- CHANGELOG, new subsection at the top of `## api_version 1 (unreleased)`:

  ```markdown
  ### Mudlet automapper

  - GMCP `Room.Info` gains `num` (a stable map number per room), `area` and `area_name`; `exits` now maps each
    direction (short names for the standard ten) to the destination's `num`, and door states move to a new `doors` map.
  ```

### Implementation Blueprint

#### Signatures & Types

```kotlin
// RoomNumbers.kt (package gups.core.model)

/** FNV-1a 32-bit over the UTF-8 bytes of [s], with the top bit cleared; never 0. */
fun mapNumberHash(s: String): Long

/**
 * Each label's map number: [hash] of the label, or, when that number is taken by a label sorting
 * earlier, of the label followed by `#1`, `#2`, … until a free one turns up.
 */
fun roomNumbers(labels: Collection<Label>, hash: (String) -> Long = ::mapNumberHash): Map<Label, Long>

// Protos.kt, inside Prototypes, after `rooms`
/** Every room's map number, for clients that key their maps by integer. */
val roomNumbers: Map<Label, Long> by lazy { roomNumbers(rooms.keys) }
```

#### Algorithmic Steps & Invariants

1. `mapNumberHash`: `h = 2166136261L`. For each byte `b` of `s.toByteArray(Charsets.UTF_8)`: `h = h xor (b.toLong() and
   0xFF)`, then `h = (h * 16777619L) and 0xFFFFFFFFL`. Finally `h = h and 0x7FFFFFFFL`, and return `if (h == 0L) 1L else
   h`. Test vectors (assert them exactly):
   - `""` → `18652613`
   - `"a"` → `1678518572`
   - `"//t:yard"` → `1786645601`
   - `"//t:hall"` → `1471930522`
   - `"//zones/tutorial:gate"` → `1687381018`
2. `roomNumbers`:
   - Sort the labels by `v`. Walk them with a `HashSet<Long>` of the numbers taken so far.
   - For each label, try `hash(v)`, then `hash("$v#1")`, `hash("$v#2")`, … and take the first number not in the set.
   - Return a `LinkedHashMap` in sorted order.
3. `World.roomInfoPayload(p)` (turn the private function into a `World.` extension, since it needs `protos`). Use a
   `LinkedHashMap` in this key order: `num`, `label`, `name`, `area`, `area_name`, `exits`, `doors`, `regions`.
   - `num`: `protos.roomNumbers.getValue(room.label)`.
   - `area`: `"//" + room.label.pkg`.
   - `area_name`: `protos.zones[room.label.pkg]?.title ?: room.label.pkg`.
   - `exits`: for each exit in `room.exits.values` (declaration order), the key is `exitKey(x.direction)` and the value
     is `protos.roomNumbers[x.to]`, which is null when the destination isn't loaded.
   - `doors`: the same keys, only for exits with `door != null`, mapped to `doorWord(door.state)`.
   - `regions`: `room.regions.sorted()`.
4. Add `private val SHORT_NAMES = Direction.ALIASES.entries.associate { (s, l) -> l to s }` and `private fun exitKey(d:
   Direction) = SHORT_NAMES[d.name] ?: d.name` in `Gmcp.kt`. `ALIASES` is `internal`, and `core` is one module, so this
   compiles.
5. Update the `when` at `gmcpPayload` (~134) to call the extension. Nothing else in delivery changes: `Room.Info` is
   still a state package recomputed in phase 7, so a hot reload that changes a room's number resends it.

### Test & Documentation Requirements

- `RoomNumbersTest`:
  - The five hash vectors.
  - The 0 → 1 rule: extract the masking step as `internal fun nonZeroMapNumber(h: Long): Long` (`h and 0x7FFFFFFF`, 0
    becomes 1), which `mapNumberHash` calls, and assert `nonZeroMapNumber(0L) == 1L` and `nonZeroMapNumber(0x80000000L)
    == 1L`.
  - Collision, with a fake `hash` that maps `"//a:x"` and `"//a:y"` to 7 and `"//a:y#1"` to 9: `//a:x` → 7, `//a:y` → 9.
  - Probing continues: when `#1` is also taken, the room gets `#2`'s number.
  - The result doesn't depend on input order: shuffled input gives the same map.
- `GmcpTest` (~78): the expected yard payload becomes the full new map. Compute expected numbers with
  `mapNumberHash(...)` and don't hardcode them. Add a case where the hall's south exit has a door: `doors` has `"s" to
  "closed"`, then `"open"` after the door opens. Keep the existing "a door here changed" resend assertion.
- Add a test with a custom-named exit (for example `portal`): it keeps its own name as the key. Add one whose
  destination label isn't loaded: the value is `null`. Build these with whatever fixture `GmcpTest` already uses to make
  rooms; read it first.
- `panels.js` typedef: `@typedef {{num: number, label: string, name: string, area: string, area_name: string, exits:
  Object<string, number|null>, doors: Object<string, string>, regions: string[]}} RoomInfo`.

### Exit Conditions

- [] `./gradlew :core:test` passes, including `RoomNumbersTest` and the updated `GmcpTest`.
- [] `./gradlew check` passes with zero warnings, after the golden regeneration step.
- [] `grep -n "Room.Info" docs/SPEC.md` shows the new row and bullets. `look` still prints full direction names.

---

## Phase 2: The mapper script and its LuaJ tests

### Objective & Scope

- **In Scope:**
  - `mapper.lua` with the mapping behavior: rooms, areas, layout, exits, stubs, special exits, doors, centering, and
    re-install safety.
  - A Lua stand-in for Mudlet's API (`fake_mudlet.lua`) and the Kotlin test that drives both.
  - The LuaJ test dependency.
  - The §2.2, §4.10 **Mudlet mapper** (script part) and §7.1 text.
- **Out of Scope:** *Do not implement these; later phases do.* `doSpeedWalk` and walking (Phase 3), zipping or serving
  the package (Phase 4), `Client.GUI` (Phase 5). Don't change `Room.Info` again.

### Context Working Set

- **Read / Reference Only:**
  - Phase 1's payload shape (Overview above).
  - `cli/src/test/kotlin/gups/cli/WebClientTest.kt`: how `cli` tests reach `webclient` resources (`runtimeOnly` puts
    them on the test classpath).
  - `gradle/libs.versions.toml`: the existing entries, for style.
- **Files to Modify / Create:**
  - create `webclient/src/main/resources/gups/mudlet/mapper.lua`
  - create `cli/src/test/resources/gups/mudlet/fake_mudlet.lua`
  - create `cli/src/test/kotlin/gups/cli/MudletMapperTest.kt`
  - `gradle/libs.versions.toml` (`luaj = "3.0.1"`, plus a comment line like its neighbours: `luaj-jse = { module =
    "org.luaj:luaj-jse", version.ref = "luaj" }`)
  - `cli/build.gradle.kts` (`testImplementation(libs.luaj.jse)`)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `net/` (the `net` module may not depend on `webclient`, even in tests, because the module-graph
  check counts test configurations), `webclient/.../webclient/` files, `files.txt`, `core/`.

### Specification & Decision Record Changes

- §2.2 module tree: in the `webclient/` entry, change "static browser client (plain ES modules with JSDoc), bundled into
  the jar" to "static browser client (plain ES modules with JSDoc) and the Mudlet mapper script, bundled into the jar".
  Keep the tree's two-space indent, and wrap the continuation line so it lines up under `static`, as its neighbours do.
- §2.2 module rules: change "`net` reads the web client's files through a class loader that `cli` hands it, so it
  doesn't depend on `webclient`." to "`net` reads the web client's files and the Mudlet mapper script through a class
  loader that `cli` hands it, so it doesn't depend on `webclient`."
- §4.10: add a new bullet directly after the **Web client** bullet and its sub-bullets:

  > - **Mudlet mapper:** `mapper.lua`, in the `webclient` module, is a Mudlet script that draws the map from
  >   `Room.Info`. It is written for Lua 5.1 and 5.2 alike.
  >   - Each room is the Mudlet room numbered `num`, holding its label as room user data `gups_label`. Other rooms with
  >     that label are deleted, and a room at `num` with a different label is replaced.
  >   - Areas are keyed by `area` (area user data `gups_area`) and named `area_name`, with ` (<area>)` added when
  >     another area already has that name. A changed `area_name` renames the area.
  >   - A new room entered by a standard direction from the previous room, in the same area, is placed one step from it
  >     that way (`n` is +y, `e` +x, `u` +z). Any other new room goes to the area's origin, or the nearest free spot
  >     around it. Rooms may overlap, and a placed room is never moved.
  >   - Each `Room.Info` sets the room's name, links every exit whose destination is mapped, gives standard exits whose
  >     destination isn't mapped a stub, adds custom exits as special exits, marks doors (except on `u` and `d`, which
  >     Mudlet can't draw doors on), removes exits and doors that are gone, and centers the map on the room. It also
  >     links the previous room's exits that lead here, so the way back is drawn at once.

- §7.1 test requirements: add after the **Telnet** bullet:

  > - **Mudlet mapper:** the script runs under LuaJ against a Lua stand-in for Mudlet's mapper API, fed `Room.Info`
  >   sequences.

- CHANGELOG bullet: "The engine ships a Mudlet mapper script that draws the map from `Room.Info` as a player explores."

### Implementation Blueprint

#### The Mudlet API the script may use (and the fake must provide)

These are the only Mudlet functions `mapper.lua` may call. Each line gives the behavior the fake implements. Room and
area ids are integers. `fake` is a global table the Kotlin test inspects.

|                            Function                            |                                                           Fake behavior                                                           |
|----------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `registerAnonymousEventHandler(event, fn)`                     | Stores `fn` (a function) under `event`. Returns a new integer id.                                                                 |
| `killAnonymousEventHandler(id)`                                | Removes that handler.                                                                                                             |
| `raiseEvent(event, ...)`                                       | Calls each handler for `event` with `(event, ...)`, in registration order.                                                        |
| `roomExists(id)`                                               | `fake.rooms[id] ~= nil`.                                                                                                          |
| `addRoom(id)`                                                  | Creates `{name = "", area = -1, x = 0, y = 0, z = 0, exits = {}, stubs = {}, special = {}, doors = {}, data = {}}`. Returns true. |
| `deleteRoom(id)`                                               | Removes it, and every exit and special exit in other rooms that points at it.                                                     |
| `setRoomName(id, name)`                                        | Sets it.                                                                                                                          |
| `setRoomArea(id, areaId)` / `getRoomArea(id)`                  | Set / get.                                                                                                                        |
| `setRoomCoordinates(id, x, y, z)` / `getRoomCoordinates(id)`   | Set / return `x, y, z`.                                                                                                           |
| `getRoomsByPosition(areaId, x, y, z)`                          | A table of the ids there, **keyed from 0** as Mudlet does. The script must test emptiness with `next(t) == nil`, never `#t`.      |
| `setExit(from, to, dir)`                                       | `dir` is a short name. `to == -1` removes it.                                                                                     |
| `setExitStub(id, dir, on)`                                     | Sets or clears `stubs[dir]`.                                                                                                      |
| `addSpecialExit(from, to, cmd)` / `removeSpecialExit(id, cmd)` | Set / clear `special[cmd]`.                                                                                                       |
| `getSpecialExitsSwap(id)`                                      | A copy of `special` (`cmd → to`).                                                                                                 |
| `setDoor(id, cmd, status)`                                     | `status` 0 clears; 1 open, 2 closed, 3 locked.                                                                                    |
| `setRoomUserData(id, key, value)` / `getRoomUserData(id, key)` | Strings. An unset key reads as `""`.                                                                                              |
| `searchRoomUserData(key, value)`                               | A 1-based list of the ids whose `key` equals `value`, ascending.                                                                  |
| `addAreaName(name)`                                            | A new id (1, 2, …) or `nil` if the name exists.                                                                                   |
| `getAreaTableSwap()`                                           | `id → name` for every area.                                                                                                       |
| `setAreaName(id, name)`                                        | Renames.                                                                                                                          |
| `setAreaUserData(id, key, value)` / `getAreaUserData(id, key)` | Strings. An unset key reads as `""`.                                                                                              |
| `centerview(id)`                                               | Sets `fake.centered = id`.                                                                                                        |
| `send(cmd, echo)`                                              | Appends `cmd` to `fake.sent`, then `raiseEvent("sysDataSendRequest", cmd)`, as Mudlet does for every outgoing command.            |
| `echo(text)`                                                   | Appends to `fake.echoed`.                                                                                                         |

Mudlet delivers GMCP by setting the global `gmcp.Room.Info` to the decoded table and raising the event
`"gmcp.Room.Info"` with no other arguments. The test does the same. A JSON null may arrive as an absent key or as some
other non-number value, so the script treats any exit value whose `type` isn't `"number"` as "destination unknown". The
test uses `false` for it.

#### Script structure (`mapper.lua`)

- One global table, `gupsMapper = gupsMapper or {}`, holding `handlers`, `last` (a deep copy of the previous
  `Room.Info`) and, in Phase 3, `walk`.
- Re-install safety: on load, `killAnonymousEventHandler` each id in `gupsMapper.handlers`, then register
  `"gmcp.Room.Info"` → `gupsMapper.onRoomInfo` and store the new ids. Mudlet re-runs the script when the package
  upgrades.
- Set `mudlet = mudlet or {}` and `mudlet.mapper_script = true`, which tells Mudlet a mapping script is present.
- Lua subset: no `goto`, no `//` or bitwise operators, no `setfenv`. Use `local unpack = table.unpack or unpack` if you
  need it. Iterate maps with `pairs`, and sort keys where order matters.

#### Algorithmic Steps & Invariants (`onRoomInfo`)

Offsets for a step in each standard direction (x, y, z), used in step 3:

|  `n`  | `ne`  |  `e`  |  `se`  |  `s`   |  `sw`   |  `w`   |  `nw`  |  `u`  |  `d`   |
|-------|-------|-------|--------|--------|---------|--------|--------|-------|--------|
| 0,1,0 | 1,1,0 | 1,0,0 | 1,-1,0 | 0,-1,0 | -1,-1,0 | -1,0,0 | -1,1,0 | 0,0,1 | 0,0,-1 |

Let `info = gmcp.Room.Info`. If `info` or `type(info.num) ~= "number"`, return.

1. **Area.**
   - Find the area id whose `getAreaUserData(id, "gups_area") == info.area` by scanning `getAreaTableSwap()`.
   - The wanted name is `info.area_name`. If another area (a different id) already has that name, the name is
     `info.area_name .. " (" .. info.area .. ")"`.
   - If no area was found, `id = addAreaName(name)` and `setAreaUserData(id, "gups_area", info.area)`.
   - If it was found but its name differs from the wanted name, call `setAreaName(id, name)`.
2. **Stale rooms.**
   - For each id in `searchRoomUserData("gups_label", info.label)` with `id ~= info.num`, call `deleteRoom(id)`.
   - If `roomExists(info.num)` and `getRoomUserData(info.num, "gups_label") ~= info.label`, call `deleteRoom(info.num)`.
3. **Create** if `not roomExists(info.num)`:
   - Call `addRoom(info.num)`, `setRoomArea(info.num, areaId)` and `setRoomUserData(info.num, "gups_label",
     info.label)`.
   - Place it:
     - `dir` = the move from `gupsMapper.last` (step 4).
     - If `dir` is a standard short name, `last.num` still exists, and `getRoomArea(last.num) == areaId`: take `last`'s
       coordinates plus that direction's offset.
     - Otherwise, take the first free spot (step 5).
     - Call `setRoomCoordinates`. An occupied target is fine: rooms may overlap.
4. **The move.** If `last` is nil, there is none. Otherwise check `last.exits` keys whose value equals `info.num`: first
   the standard names in the table's order (`n, ne, e, se, s, sw, w, nw, u, d`), then the remaining keys in sorted
   order. Take the first match, or none.
5. **First free spot.** For `r = 0, 20`: for `y = r` down to `-r`: for `x = -r` up to `r`: if `math.max(math.abs(x),
   math.abs(y)) == r` and `next(getRoomsByPosition(areaId, x, y, 0)) == nil`, return `x, y, 0`. If none is free, return
   `0, 0, 0`.
6. **Every visit**, whether the room is new or not:
   - `setRoomName(info.num, info.name)`.
   - For each standard name `d`:
     - If `info.exits[d]` is a number and `roomExists(it)`: `setExit(info.num, it, d)` and `setExitStub(info.num, d,
       false)`.
     - If `info.exits[d]` is present but not mapped (or not a number): `setExit(info.num, -1, d)` and
       `setExitStub(info.num, d, true)`.
     - If it's absent: `setExit(info.num, -1, d)` and `setExitStub(info.num, d, false)`.
   - For each other key `k` of `info.exits` (sorted): if its value is a number and `roomExists(it)`,
     `addSpecialExit(info.num, it, k)`. Then, for each `cmd` in `getSpecialExitsSwap(info.num)` that isn't a custom key
     of `info.exits` with a mapped destination, `removeSpecialExit(info.num, cmd)`.
   - Doors: Mudlet's `setDoor` takes only the eight compass names (`n`, `ne`, `e`, `se`, `s`, `sw`, `w`, `nw`) or a
     special exit's command, never `u` or `d`.
     - For every key `k` of `info.exits` other than `u` and `d`, call `setDoor(info.num, k, s)`, where `s` is 1/2/3 for
       `"open"`/`"closed"`/`"locked"` in `info.doors[k]`, else 0.
     - Call `setDoor(info.num, d, 0)` for each compass `d` absent from `info.exits`.
     - A door on `u` or `d` isn't drawn.
   - Back-link: if `gupsMapper.last` is set, `last.num ~= info.num` and `roomExists(last.num)`, then for each key `k` of
     `last.exits` whose value equals `info.num`:
     - For a standard name, call `setExit(last.num, info.num, k)` and `setExitStub(last.num, k, false)`.
     - Otherwise, call `addSpecialExit(last.num, info.num, k)`.

     When the player left, this room wasn't mapped yet, so the previous room still shows a stub.

   - `centerview(info.num)`.

   - `gupsMapper.last` = a deep copy of `info`. (Keep the copy: Mudlet reuses the `gmcp` table.)

7. **Invariant:** a room that exists and has the right label is never re-placed. Only step 3 calls `setRoomCoordinates`.

#### The Kotlin driver (`MudletMapperTest`)

- Build a `Globals` with `org.luaj.vm2.lib.jse.JsePlatform.standardGlobals()`.
- Load `fake_mudlet.lua` from the test classpath, then `gups/mudlet/mapper.lua` through
  `javaClass.classLoader.getResourceAsStream`.
- A helper `roomInfo(num, label, name, area, areaName, exits: Map<String, Any?>, doors: Map<String, String> = mapOf())`
  builds a `LuaTable`, with a null exit value becoming `LuaValue.FALSE`. Another helper sets `gmcp.Room.Info` and calls
  `raiseEvent("gmcp.Room.Info")`.
- Read results back from `fake` (for example `globals["fake"]["rooms"][num]["x"].toint()`).

### Test & Documentation Requirements

`MudletMapperTest`, one test per behavior. Use small integers for `num` in these tests; the script doesn't care where
they come from.

- The first room goes to (0,0,0) in a new area named `area_name`, with user data `gups_area`, and the map centers on it.
- Walking `n` then `e` places rooms at (0,1,0) and (1,1,0).
- Right after the second room's `Room.Info`, with no revisit, the first room's `n` is linked to it and no longer a stub.
  The same holds for a custom exit, which becomes a special exit.
- An unmapped standard exit gets a stub. After its destination is mapped and the room is visited again, the stub is gone
  and the exit is linked.
- A custom exit (`portal`) to a mapped room becomes a special exit, and it is removed when a later visit no longer lists
  it.
- Doors: `closed` gives status 2, then `open` gives 1, and a door that is gone gives 0.
- A door on `u` never calls `setDoor`. The fake records every `setDoor` call, and its `dir` must be a compass name or a
  special exit's command; assert this.
- Arriving through a custom exit places the new room at the first free spot (with (0,0,0) taken, that's (-1,1,0)).
- Arriving with no linking exit (a teleport) does the same.
- Entering a different area starts it at that area's (0,0,0).
- Revisiting a room never moves it, even after the player arrives from a different direction.
- Overlap: a loop whose geometry doesn't close places a room on an occupied spot without moving the other one.
- Duplicate titles: two areas with `area_name` `"Caves"` become `"Caves"` and `"Caves (//zones/b)"`. A changed
  `area_name` renames the area.
- Label change: a `Room.Info` whose `num` holds a room with another label replaces it. A label seen before at another
  `num` deletes the old room.
- A null destination (`false`) gives a stub and no error.
- Loading the script twice leaves one handler: one event creates one room and no duplicate calls.
- A Lua error in the script fails the test. LuaJ throws `LuaError`, so let it propagate.

### Exit Conditions

- [] `./gradlew :cli:test --tests 'gups.cli.MudletMapperTest'` passes.
- [] `./gradlew check` passes with zero warnings, including `checkModuleGraph`.
- [] `mapper.lua` uses no Lua 5.2-only or 5.3 syntax: re-read it for `goto`, `::label::`, `//`, and the bitwise
  operators `&`, `|`, `~` (other than `~=`), `<<` and `>>`, outside strings and comments.

---

## Phase 3: Click-to-walk

### Objective & Scope

- **In Scope:** the global `doSpeedWalk()`, walking one step per `Room.Info`, interruption, and quiet cancellation on
  player input, with tests and spec text.
- **Out of Scope:** *Do not implement these; later phases do.* Packaging, routes, `Client.GUI`. Don't change the mapping
  steps from Phase 2, except to call the walk step from `onRoomInfo`.

### Context Working Set

- **Read / Reference Only:** Phase 2's `mapper.lua`, `fake_mudlet.lua` and `MudletMapperTest.kt`.
- **Files to Modify / Create:**
  - `webclient/src/main/resources/gups/mudlet/mapper.lua`
  - `cli/src/test/resources/gups/mudlet/fake_mudlet.lua` (only if a helper is missing)
  - `cli/src/test/kotlin/gups/cli/MudletMapperTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** everything outside these files.

### Specification & Decision Record Changes

- §4.10 **Mudlet mapper** bullet: add a last sub-bullet:

  > - Double-clicking a room on the map walks there one step at a time: the script sends the first direction, and each
  >   `Room.Info` for the next room on the path sends the next one. A `Room.Info` for any other room stops the walk with
  >   "Walk interrupted.", and a command the player sends stops it quietly. One step at a time keeps a long walk within
  >   the input limits. A step that fails, such as into a closed door, produces no `Room.Info`, so the walk sends
  >   nothing more and waits, without a message, until the player's next command ends it.

- CHANGELOG bullet: "Double-clicking a room on the Mudlet map walks there, one step per room."

### Implementation Blueprint

- **Mudlet's contract.** When the player double-clicks a room, Mudlet fills the globals `speedWalkDir` (a 1-based list
  of commands: short direction names, or a special exit's command) and `speedWalkPath` (a 1-based list of the room ids
  after each step), then calls the global function `doSpeedWalk()`. The fake doesn't need anything new: the test sets
  both globals and calls `doSpeedWalk()`.
- **State:** `gupsMapper.walk = {dirs = <copy>, path = <copy>, i = 0}` or nil, plus `gupsMapper.sending` (boolean).
- **Handlers:** also register `"sysDataSendRequest"` → `gupsMapper.onSend`, with the same re-install handling.
- **Steps:**
  1. `doSpeedWalk()`: if `speedWalkDir` is empty or nil, do nothing. Otherwise set `walk` from copies of both globals
     and call `gupsMapper.step()`.
  2. `step()`: `walk.i = walk.i + 1`. If `walk.i > #walk.dirs`, set `walk = nil` and return. Otherwise set `sending =
     true`, call `send(walk.dirs[walk.i], false)`, and set `sending = false`.
  3. `onRoomInfo`, at its very end, after the mapping: if `walk` is set, then if `info.num == walk.path[walk.i]`, call
     `step()`. Otherwise set `walk = nil` and call `echo("Walk interrupted.\n")`.
  4. `onSend(event, cmd)`: if `sending`, return. Otherwise set `walk = nil` (quietly).
- **Invariant:** at most one command is sent per `Room.Info`, so a walk can never get ahead of the player.

### Test & Documentation Requirements

- A three-step path sends exactly one command per arriving `Room.Info`, in order, and nothing after the last room.
- A `Room.Info` for a room not on the path (the player fled or was teleported mid-walk) echoes `Walk interrupted.` and
  sends nothing further.
- A blocked step (no `Room.Info` arrives after a send) sends nothing more. A following `raiseEvent("sysDataSendRequest",
  "open door")` ends the walk with no echo. `Room.Info` is a state package resent only when it changes, so a failed move
  produces none.
- `raiseEvent("sysDataSendRequest", "look")` mid-walk stops it with no echo, and the next `Room.Info` sends nothing.
- The script's own `send` doesn't cancel the walk (the fake raises `sysDataSendRequest` from `send`).
- A special-exit step (`portal`) is sent as typed.
- An empty `speedWalkDir` does nothing.

### Exit Conditions

- [] `./gradlew :cli:test --tests 'gups.cli.MudletMapperTest'` passes.
- [] `./gradlew check` passes with zero warnings.

---

## Phase 4: Building and serving `gups-mapper.mpackage`

### Objective & Scope

- **In Scope:**
  - `MudletPackage` in `net`: read the script, build the zip, compute the version.
  - The route on `WebServer` and `DocsServer`, and `cli` handing the package to both.
  - Spec text for the endpoint and the packaging.
- **Out of Scope:** *Do not implement these; later phases do.* `Client.GUI`, `public_url`, any GMCP change, README docs.
  Don't change `mapper.lua`.

### Context Working Set

- **Read / Reference Only:**
  - `net/src/main/kotlin/gups/net/WebServer.kt`: `WebAssets` (~79: hash style, `fromClasspath` loader pattern),
    `mountDocs` (~135), `DocsServer` (~151), the `WebServer` constructor (~218) and `routing { … }` (~258).
  - `cli/src/main/kotlin/gups/cli/GupsServer.kt`: `webServer` (~172) and `docsServer` (~194) lazies.
  - `net/src/test/kotlin/gups/net/WebServerTest.kt`: how HTTP routes are tested (plain HTTP, no certificate).
- **Files to Modify / Create:**
  - create `net/src/main/kotlin/gups/net/MudletPackage.kt`
  - `net/src/main/kotlin/gups/net/WebServer.kt`
  - `cli/src/main/kotlin/gups/cli/GupsServer.kt`
  - create `net/src/test/kotlin/gups/net/MudletPackageTest.kt`
  - `net/src/test/kotlin/gups/net/WebServerTest.kt` (route tests)
  - create `cli/src/test/kotlin/gups/cli/MudletPackageClasspathTest.kt` (the real script is on `cli`'s test classpath)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `core/`, `Telnet.kt`, `TelnetServer.kt`, `ServeCommand.kt`, `ServerConfig.kt`, `mapper.lua`.

### Specification & Decision Record Changes

- §4.10 HTTPS endpoint table: add a row after `/docs`: ``| `GET /mudlet/gups-mapper.mpackage` | The Mudlet mapper
  package (below), no login needed, `no-cache`. |``

- §4.10 **Mudlet mapper** bullet: replace its first sentence ("`mapper.lua`, in the `webclient` module, is a Mudlet
  script that draws the map from `Room.Info`.") with:

  > `mapper.lua`, in the `webclient` module, is a Mudlet script that draws the map from `Room.Info`. At startup the
  > server wraps it as the package `gups-mapper.mpackage`: a zip holding `config.lua` (which names the package
  > `gups-mapper` and gives its version) and `gups-mapper.xml` (the script in Mudlet's XML package format), with fixed
  > entry order and timestamps, so the same script always gives the same bytes. Its version is the first 16 hex digits
  > of a SHA-256 over the script, so it changes exactly when the script does. `--prod` serves it on `--https`, and
  > `--dev` on the docs port.

- CHANGELOG bullet: "The server serves the Mudlet mapper as `/mudlet/gups-mapper.mpackage`."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// MudletPackage.kt (package gups.net)

/** The Mudlet mapper script, packaged as a `.mpackage` Mudlet can install. */
class MudletPackage(script: ByteArray) {
  /** First 16 hex digits of a SHA-256 over the script. */
  val version: String

  /** The `.mpackage` zip: `config.lua`, then `gups-mapper.xml`. */
  val bytes: ByteArray

  companion object {
    const val NAME = "gups-mapper"
    const val PATH = "/mudlet/$NAME.mpackage"

    /** The package of `gups/mudlet/mapper.lua` on [loader], or null when it isn't there. */
    fun fromClasspath(loader: ClassLoader = MudletPackage::class.java.classLoader): MudletPackage?
  }
}

// WebServer.kt
/** Serves [pkg] at [MudletPackage.PATH] with no login. */
fun Route.mountMudlet(pkg: MudletPackage)
```

Add `private val mudlet: MudletPackage? = null` as the last parameter of both `WebServer` and `DocsServer`. In each
`routing { … }`, add `mudlet?.let { mountMudlet(it) }`.

#### Package file templates

Both files are exact, with LF line endings and UTF-8. `config.lua`:

```lua
mpackage = [[gups-mapper]]
author = [[GUPS]]
title = [[GUPS mapper]]
description = [[Draws the map from the server's GMCP Room.Info as you explore.]]
version = [[<version>]]
```

`gups-mapper.xml`, where `ESCAPED` is the escaped script:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE MudletPackage>
<MudletPackage version="1.001">
    <ScriptPackage>
        <Script isActive="yes" isFolder="no">
            <name>gups-mapper</name>
            <packageName>gups-mapper</packageName>
            <script>ESCAPED</script>
            <eventHandlerList />
        </Script>
    </ScriptPackage>
</MudletPackage>
```

#### Algorithmic Steps & Invariants

1. `version`: `MessageDigest.getInstance("SHA-256").digest(script)`. Take the first 8 bytes as `%02x`, the same as
   `WebAssets.hash`.
2. `config.lua` is the text in [Package file templates](#package-file-templates), with `<version>` replaced.
3. `gups-mapper.xml` is the text in [Package file templates](#package-file-templates). `ESCAPED` is the script decoded
   as UTF-8 with `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;` and `"` → `&quot;` (`&` first).
4. Zip: a `ZipOutputStream` over a `ByteArrayOutputStream`, DEFLATED. Write the entries in order, `config.lua` then
   `gups-mapper.xml`. Call `entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))` on each, which doesn't depend on the
   time zone. No other entry fields.
5. `mountMudlet`: `get(MudletPackage.PATH)` responds with `Cache-Control: no-cache` and `respondBytes(pkg.bytes,
   ContentType.Application.Zip)`.
6. `GupsServer`: one `private val mudlet by lazy { MudletPackage.fromClasspath() }`, passed to both the `WebServer(…)`
   and the `DocsServer(…)` constructors.

### Test & Documentation Requirements

- `MudletPackageTest` (net, with an inline script such as `"-- a < b & c\nprint(1)\n".toByteArray()`):
  - The zip has exactly the two entries, in order.
  - `config.lua` names `gups-mapper` and contains the version.
  - The XML parses with `javax.xml.parsers.DocumentBuilderFactory`, and its `<script>` text equals the original script.
  - Building twice gives identical bytes and version, also with `TimeZone.setDefault` changed between the two builds.
    Restore it in `finally`.
  - A one-byte change to the script changes the version.
  - The version is 16 lowercase hex characters.
- `WebServerTest`:
  - `GET /mudlet/gups-mapper.mpackage` answers 200 with `application/zip`, `no-cache` and the package bytes, and needs
    no cookie.
  - Without a package it's 404.
  - The same two checks on `DocsServer`.
- `MudletPackageClasspathTest` (cli): `MudletPackage.fromClasspath()` is not null and its XML contains `gupsMapper`.

### Exit Conditions

- [] `./gradlew :net:test :cli:test` passes.
- [] `./gradlew check` passes with zero warnings, including `checkModuleGraph` (`net` gains no project dependency).
- [] Manual (optional, if a Mudlet install is available): `gups serve --dev`, open
  `http://127.0.0.1:4080/mudlet/gups-mapper.mpackage`, and install it in Mudlet's Package Manager without errors. Two
  Mudlet facts were checked only against summaries of its docs and source, so confirm them here if you can, and report
  either one in your summary if it's wrong:
  - `Client.GUI`'s `version` is compared as a string, so a hex version triggers upgrades.
  - `setDoor` rejects `u`/`d`.

---

## Phase 5: `Client.GUI` in the engine

### Objective & Scope

- **In Scope:**
  - The `ClientGui` value and `World.clientGui`.
  - Sending `Client.GUI` in `attach`.
  - The delivery exception in every sink (`TelnetConnection`, the WebSocket sink, `CapturingSink`).
  - `"Client.GUI"` in `ENGINE_GMCP_PACKAGES`.
  - Spec text.
- **Out of Scope:** *Do not implement these; later phases do.* Setting `world.clientGui` from the CLI, `--public-url`,
  and README docs. In this phase only tests set it.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt`: `World.gmcp` (~57) and `ENGINE_GMCP_PACKAGES` (~29).
  - `script/src/main/kotlin/gups/script/Declarations.kt` (~513) and
    `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginRegistryImpl.kt` (~262, ~291): how the engine set refuses
    overlapping declarations (no edit needed).
  - `net/src/test/kotlin/gups/net/TlsGmcpTest.kt` and `TelnetConnectionTest.kt` (~56, the Mudlet-like client) for
    fixtures.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Gmcp.kt` (the `ClientGui` class, the package set, `UNSOLICITED_GMCP`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (one property)
  - `core/src/main/kotlin/gups/core/world/Sessions.kt` (`attach` ~78)
  - `core/src/main/kotlin/gups/core/Seams.kt` (`OutputSink.wantsGmcp` KDoc ~125, `CapturingSink.wantsGmcp` ~166)
  - `net/src/main/kotlin/gups/net/Telnet.kt` (`wantsGmcp` ~467)
  - `net/src/main/kotlin/gups/net/WebServer.kt` (the socket sink's `wantsGmcp` ~460)
  - tests: `core/src/test/kotlin/gups/core/world/GmcpTest.kt`, `net/src/test/kotlin/gups/net/TelnetConnectionTest.kt`,
    `net/src/test/kotlin/gups/net/WebServerTest.kt`, and a script-side test that `gmcp_package("Client.GUI", …)` fails
    to load (next to the existing engine-overlap test; find it with `grep -rn "ENGINE_GMCP\|engine package"
    script/src/test`)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `cli/`, `ServerConfig.kt`, `mapper.lua`, `MudletPackage.kt`.

### Specification & Decision Record Changes

- §4.10 GMCP packages table: add a row after `Char.Group`: ``| `Client.GUI` | `{"version", "url"}` | Once each time a
  session takes a character, to every client with GMCP on, when the server knows its public URL (§2.4) |``

- §4.10 **GMCP delivery**, first bullet: after "…compared case-insensitively." insert "The one exception is
  `Client.GUI`, which goes to every client with GMCP on whatever `Core.Supports` lists, because Mudlet doesn't list it."

- §4.10 **GMCP delivery**: change "Events (`Comm.Channel.Text`, `Gups.Trace`, `Char.Creation`) are never cached." to
  "Events (`Comm.Channel.Text`, `Gups.Trace`, `Char.Creation`, `Client.GUI`) are never cached."

- §4.10 **GMCP payloads**: add after the `Char.Group` bullet:

  > - `Client.GUI`: `version` is the Mudlet mapper package's version and `url` is the public URL followed by
  >   `/mudlet/gups-mapper.mpackage`. Mudlet installs the package, or replaces an older version, without asking (unless
  >   the player turned that off); a client that knows nothing of it ignores it.

- CHANGELOG bullet: "GMCP clients are sent `Client.GUI` when they take a character, so Mudlet installs the mapper
  package itself."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// Gmcp.kt
/** Where clients can fetch the Mudlet mapper package, and its version. */
data class ClientGui(val version: String, val url: String)

/** Packages sent to every client with GMCP on, whatever it listed in `Core.Supports`. */
val UNSOLICITED_GMCP: Set<String> = setOf("Client.GUI")

/** True if [pkg] is one of [UNSOLICITED_GMCP], ignoring case. */
fun isUnsolicitedGmcp(pkg: String): Boolean

// ENGINE_GMCP_PACKAGES: add "Client.GUI" to the listOf(...) of events.

// World.kt, next to other runtime-settable properties
/**
 * The Mudlet package offered to clients that take a character, or null when the server has no
 * public URL. Set once before any listener starts.
 */
@Volatile var clientGui: ClientGui? = null
```

#### Algorithmic Steps & Invariants

1. `attach` (Sessions.kt ~78): after `player.session = session`, add `clientGui?.let { gmcp(player, "Client.GUI",
   mapOf("version" to it.version, "url" to it.url)) }`. `World.gmcp` already checks `wantsGmcp` and queues it after the
   text, before the prompt.
2. `TelnetConnection.wantsGmcp`: `gmcp && (isUnsolicitedGmcp(pkg) || supportsGmcp(gmcpSupports, pkg))`.
3. The WebSocket sink's `wantsGmcp`: `isUnsolicitedGmcp(pkg) || supportsGmcp(supports, pkg)`. A socket always speaks
   GMCP frames.
4. `CapturingSink.wantsGmcp`: `supports.isNotEmpty() && isUnsolicitedGmcp(pkg) || supports.any { gmcpWithin(pkg, it) }`.
   A harness player spawned with no `gmcp` list has no GMCP, so it still gets nothing.
5. Update the `OutputSink.wantsGmcp` KDoc: "True if the client should get GMCP package [pkg]: it listed the package or a
   module above it in `Core.Supports`, or [pkg] is one the engine sends to every client with GMCP on."
6. **Invariant:** with `clientGui == null` (every existing test and the harness), nothing anyone sees changes.

### Test & Documentation Requirements

- `GmcpTest`:
  - With `w.clientGui` set and a sink supporting only `Char`, attaching gets exactly one `Client.GUI` with the payload.
  - A reconnect (a second `attach`) gets it again.
  - Running more pulses sends no second one.
  - With `clientGui` null, no `Client.GUI` is sent.
  - A `CapturingSink()` with no supports gets nothing.
- `TelnetConnectionTest`: after `DO GMCP` and a `Core.Supports.Set ["Char 1"]`, `wantsGmcp("Client.GUI")` is true. It is
  also true in lower case. Before GMCP is on it is false.
- `WebServerTest`: the socket sink's `wantsGmcp("Client.GUI")` is true with an empty supports list. Mirror the existing
  `Room.Info` sink test (~316).
- Script load test: a `gmcp_package("Client.GUI", ...)` declaration is refused with the existing overlap error.
- Existing tests that assert `ENGINE_GMCP_PACKAGES` or the docs listing of engine packages: update them to include
  `Client.GUI`. The golden regeneration step shows any such diff.

### Exit Conditions

- [] `./gradlew :core:test :net:test :script:test :plugin-api:test` passes.
- [] `./gradlew check` passes with zero warnings.
- [] A harness scenario without GMCP is byte-identical: the existing scenario-based tests pass unchanged.

---

## Phase 6: `public_url`, wiring, docs and finalization

### Objective & Scope

- **In Scope:**
  - `--public-url` and `server(public_url = ...)`, with validation.
  - `GupsServer` setting `world.clientGui` in both profiles, and the startup warning.
  - Starting `DocsServer` before the listeners.
  - Spec §2.4, the README updates, an end-to-end check, and the backlog bookkeeping.
- **Out of Scope:** *Do not implement these.* Room environments for map coloring, builder-declared coordinates, masking
  exits, and a server-built map file (`Client.Map`). None of them is part of this milestone.

### Context Working Set

- **Read / Reference Only:**
  - `cli/src/main/kotlin/gups/cli/ServeCommand.kt`: the `openSignup` option (~134), `rejectProdOnlyOptions` (~197),
    `prodServer` (~222) and `prodListeners`.
  - `cli/src/main/kotlin/gups/cli/ServerSetup.kt`: the `loginSettings` / `deploySettings` style of "flag, else
    configuration".
  - `cli/src/main/kotlin/gups/cli/GupsServer.kt`: `ProdOptions` (~71) and `run` (~681: `ports` is built with docs last,
    so `ready()` gets the telnet port).
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/ServerConfig.kt` (field, `DeclParam`, parse)
  - `cli/src/main/kotlin/gups/cli/ServeCommand.kt`
  - `cli/src/main/kotlin/gups/cli/ServerSetup.kt` (a `publicUrl(...)` helper)
  - `cli/src/main/kotlin/gups/cli/GupsServer.kt`
  - tests: the existing `ServerConfig` test in `script/src/test`, the `ServeCommand`/`GupsServer` tests in
    `cli/src/test` (find them with `grep -rln "open_signup\|openSignup" */src/test`)
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, `README.md`, `template-world/README.md`, `docs/DESIGN_NEEDED.md`
- **Do Not Touch:** `core/`, `net/`, `mapper.lua`.

### Specification & Decision Record Changes

- §2.4 profile table, `--dev` row: after "…(`--docs-port PORT` changes the port; `0` picks a free one)." insert " The
  same port serves the Mudlet mapper package, which GMCP clients are offered at that address (§4.10)."
- §2.4 `server(...)` example: add after the `https = ":443",` line: `public_url = "https://mud.example.edu", # where
  players reach https; offers Mudlet the mapper (§4.10)`
- §2.4 flag list: add after the **Listeners** bullet:

  > - **Public URL:** `--public-url`/`public_url`, the absolute `http` or `https` address players reach the HTTPS
  >   listener at, such as `https://mud.example.edu`, or one with a path behind a proxy. It needs `--https`. With it,
  >   GMCP clients are offered the Mudlet mapper package (§4.10); without it they aren't, and startup logs a warning.

- CHANGELOG bullet: "`--public-url` / `public_url` tells a production server its public address so Mudlet can be offered
  the mapper; `--dev` offers it from the docs port automatically."

### Implementation Blueprint

#### Signatures & Types

```kotlin
// ServerConfig.kt: new field after `https`
/** The address players reach `https` at; null offers clients no Mudlet package. */
val publicUrl: String? = null,
// DeclParam("public_url", "str", "Public http(s) URL players reach the HTTPS listener at.", default = "None")
// parse: publicUrl = d.str("public_url")

// ServeCommand.kt
val publicUrl by option("--public-url", metavar = "URL", help = "--prod: public URL of --https, e.g. https://mud.example.edu.")

// ServerSetup.kt
/** The checked public URL of the flag, else the configuration, without a trailing slash; null when neither is set. */
internal fun publicUrl(flag: String?, cfg: ServerConfig, hasHttps: Boolean): String?

// GupsServer.kt, ProdOptions: new last field
/** The public URL of the HTTPS listener, without a trailing slash; null for none. */
val publicUrl: String? = null,
```

#### Algorithmic Steps & Invariants

1. `publicUrl(...)`:
   - Take `flag ?: cfg.publicUrl`, or return null if neither is set.
   - Parse it with `java.net.URI`. It must have scheme `http` or `https` (case-insensitive), a non-null host, and no
     query or fragment. Otherwise throw `UsageError("--public-url must be an absolute http or https URL such as
     https://mud.example.edu")`.
   - If `!hasHttps`, throw `UsageError("--public-url needs --https, which serves the Mudlet package")`.
   - Return it with trailing `/` characters removed.
2. `rejectProdOnlyOptions`: add `publicUrl` to the `prodOnly` list. The existing message is fine.
3. `prodServer`: compute `publicUrl(publicUrl, cfg, listeners.site != null)` and pass it into `ProdOptions`.
4. `GupsServer.run` (~681):
   - Start `docsServer` first and keep its port.
   - Then set `world.clientGui`, before any listener starts:
     - `--dev`: if the docs port is known and `mudlet != null`, use `ClientGui(mudlet.version,
       "http://127.0.0.1:$docsPort${MudletPackage.PATH}")`.
     - `--prod`: if `prod.publicUrl != null` and `mudlet != null`, use `ClientGui(mudlet.version, prod.publicUrl +
       MudletPackage.PATH)`.
     - Otherwise null. When `prod.web != null && prod.publicUrl == null`, log once, through the server's existing log
       callback: "No public_url is set, so Mudlet clients won't be offered the mapper package."
   - Then start the listeners as now.
   - Build `ports` with `docs` still added last, so `ready(ports.values.first())` still gets the telnet port.
5. **Invariant:** `world.clientGui` is written once, before any session can exist. `@Volatile` makes it visible to the
   world thread.

### Test & Documentation Requirements

- `ServerConfig` test: `public_url = "https://x.edu"` parses. Omitting it gives null.
- `publicUrl` unit tests:
  - The flag beats the configuration.
  - A trailing slash is dropped.
  - `https://x.edu/gups/` becomes `https://x.edu/gups`.
  - `ftp://x`, `x.edu`, `https://x.edu/?a=1` and `https:///p` give the URL message.
  - A valid URL without `--https` gives the "needs --https" message.
- `--dev --public-url https://x` is a usage error with the existing prod-only message.
- `GupsServer` test (dev): after `run` starts with `docsPort = 0`, `world.clientGui!!.url ==
  "http://127.0.0.1:${ports["docs"]}/mudlet/gups-mapper.mpackage"`. The first value in `ports` is still the telnet port.
- `GupsServer` test (prod with `--https` and `--self-signed`, no public URL): `clientGui` is null and the warning is
  logged once. With a public URL, the URL is the base followed by `/mudlet/gups-mapper.mpackage`.
- `README.md` (engine): add `public_url = "https://mud.example.edu",` to any `server(...)` example, and add
  `--public-url https://mud.example.edu` to the production `gups serve --prod` example.
- `template-world/README.md`: add a section `## Playing with Mudlet` after `## Running Locally`:
  - With `gups serve --dev`, connect Mudlet to `localhost` port `4000`. When you enter the world, Mudlet installs the
    GUPS mapper by itself and the map fills in as you walk.
  - On the course server, it works the same once the instructor has set `public_url`.
  - If the server can't offer it (no `public_url`, or "Allow server to install script packages" is off in Mudlet's
    settings), download `<server>/mudlet/gups-mapper.mpackage` and install it from Mudlet's Package Manager.
  - Double-click a room on the map to walk there.
  - Keep it short; no screenshots.

### Finalization & Backlog Update

- End-to-end check (`gups scenario` can't reach HTTP or `Client.GUI`, since harness players have no URL, so use the
  server itself):
  - Run `./gradlew :cli:installDist`, then `cli/build/install/gups/bin/gups serve --dev --world template-world
    --docs-port 0` in the background, against a scratch database in `local-world-test/` (`--db
    local-world-test/dev.db`).
  - Read the printed docs port.
  - `curl -s -o local-world-test/p.mpackage http://127.0.0.1:<port>/mudlet/gups-mapper.mpackage`, and check with `unzip
    -l` that it lists `config.lua` and `gups-mapper.xml`.
  - Stop the server.
  - Write `local-world-test/mudlet_scenario.star`: a player spawned with `gmcp = ["Room"]` at `:gate`, walking a few
    rooms of the tutorial zone. Print `p.gmcp("Room.Info")` and check `num`, `area`, `area_name` and that each exit's
    `num` matches the `num` reported when you arrive there. Run it with `gups scenario --world template-world --package
    //zones/tutorial`.
- In `docs/DESIGN_NEEDED.md`, mark the entry completed without deleting it: change the heading `### Mudlet automapper
  support` to exactly `### Mudlet automapper support (Done in Milestone 56)`.
- Re-read §4.10 (the `Room.Info` and `Client.GUI` rows and bullets, the delivery exception, the endpoint, and the Mudlet
  mapper bullet), §2.4 and §2.2 against the code. Field names, key order, the hash, the URL path and the messages must
  match.
- Confirm the CHANGELOG subsection `### Mudlet automapper` has a bullet for every phase.
- Run `./gradlew check` and `./gradlew markdownCheck` outside the sandbox; both must pass.
- Run `pre_commit_code_health_safeguard`; it must pass.
- Delete `local-world-test/`.
