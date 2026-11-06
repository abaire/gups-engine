# Milestone 38: Pet Ownership, Orders, and Owner-Bound Combat

## Overview & Agreed Design

- **Source `DESIGN_NEEDED`:** `docs/DESIGN_NEEDED.md`, heading `### Pets and followers bonded to a player` (under "Mobs
  and AI"), including its "Revisit from M37" note.
- **Design Summary:**
  1. **Ownership is its own field.** `Mob.owner: EntityId?` names the player a mob belongs to. It is separate from
     `leader` (whom a `follow` behavior is following) and `summonedBy` (who summoned it; such a mob gives no XP or
     loot). `World.ownerOf` reads only `owner`, so a `follow("players")` mob that latches onto a passer-by no longer
     counts as that player's pet for `xp_share`, groups or `allies_near`. The word "bond" leaves the engine.
  2. **Verbs:** `ctx.set_owner(mob, player)` and `ctx.clear_owner(mob)`, named after the `set_active`/`clear_active`
     pair. `set_owner` replaces any previous owner. An owned mob carries the active reason `owner:<player_id>` while its
     owner is in the world, and setting an owner releases the mob from its spawn rule so the zone can respawn another.
     The actor view gets an `owner` field.
  3. **`summon` sets the owner** when the caster is a player. `summonedBy` and the owner are set before the summoned
     mob's own `on_spawn` runs, so that hook sees both.
  4. **Pets are stored with their owner.** When the owner quits, including a link-dead expiry, every in-world pet of
     theirs leaves the world and is saved with the character: vitals, affects, timers and carried items. At login each
     pet comes back beside the player. A pet saved in the world, for example by a shutdown while its owner was online,
     is also held out of the world at boot until its owner logs in. Affects keep running out while a pet is stored,
     because their `expiresAt` is an absolute pulse.
  5. **Owned behavior set.** While a mob has an owner, it runs an **owned behavior list** instead of its prototype's
     `behaviors`:
     - Two new built-in behaviors: `heel(priority=30)` follows the owner with no distance limit; `defend(group=True,
       priority=50)` attacks whatever the owner, or with `group` the owner's in-room group members, are fighting.
     - Every built-in behavior that has a priority takes `priority=`. The §4.7 Priority column becomes the default.
     - **Default owned list** (when the world gives none): the prototype's `flee` and `sentinel` behaviors, in
       declaration order, then `heel()`, then `defend()` when `combat` is on.
     - **Rules export** `owned_behaviors(ctx)` (optional, like `xp_share`): `ctx.actor` is the mob, `ctx.target` the
       owner, `ctx.behaviors` the prototype's behavior list. It returns the owned list. Behavior constructors may be
       called inside it.
     - **Plugin seam** `addOwnedBehaviors(doc, adjust)`: runs after the rules export, in plugin-id order, each handler
       turning the list so far, as plain descriptors, into a new list.
     - The owned list is computed when an owner is set, when a stored pet is restored at login, and after a reload. It
       is cached on the mob and never saved.
     - **Engine safety rule:** an owned mob's `aggressive` and `assist` never pick its owner's side: the owner, the
       owner's group members, or a pet of any of them.
  6. **`order` command.** `order <pet> <command line>` queues the line on one of the player's pets in the room, exactly
     as `ctx.act` does; `order pets <command line>` does so for each of them in ascending id. The player pays no lag (a
     fast-lane command). Vetoes: a new `ORDER` plugin restriction point, then a new `before_order` hook. **An ordered
     action is re-checked when it runs:** if the owner is no longer in the pet's room by then, the action is dropped.
     This stops "order the pet to attack, then leave before the attack lands" and "order the pet into the next room,
     then order it to attack there".
  7. **Owner engagement follows the pet.** When a pet becomes engaged with an actor, its owner is engaged with that
     actor too, if the owner is in the same room and the actor is not the owner itself. That includes players, other
     players' pets and the owner's own groupmates: a player can't use a pet to attack anyone without taking part in the
     fight. When two players' pets fight, both owners are drawn in, and so end up fighting each other. The engaged owner
     auto-attacks like anyone engaged; a world that does not want that vetoes it in its rules or hooks.
     - **Gaining an owner ends the mob's current fights with that owner's side.** `set_owner` (and a player's `summon`)
       ends every engagement between the mob and its new owner's side, firing `on_disengage` with `"stop"`, so a charm
       spell doesn't leave the new pet hitting the player who charmed it. This is a one-time step: nothing stops a pet
       from fighting its owner afterwards (`order`, `start_combat`, `think`, a world script).
  8. **Pet disengagement follows the owner.** Whenever the owner's engagement with an opponent ends (a flee, leaving the
     room, `stop_combat`, the owner's death, a quit), every pet of that owner in that room that is engaged with that
     opponent is disengaged from it too, and its `on_disengage` gets the owner's event. A pet attacked while its owner
     is away can still fight back. A world that keeps `aggressive` in its owned list lets pets start fights without
     their owner; the spec says so.
  9. **Hooks:** `before_summon` (veto) and `on_summon` on Room and Mob (observer), for both the `summon` primitive and
     the `ctx.summon` verb; a global `on_owner_change` with `event` `"set"` or `"clear"`; and `before_order` (veto) on
     the pet and the room.
- **Specification Impact:** §3.3 (mob state, groups), §3.6 (persistence), §3.10 (feature table), §4.4 (hook catalog),
  §4.5 (verbs, view fields, ctx fields), §4.6 (rules exports and rules ctx), §4.7 (behaviors), §4.9 (commands), §4.15
  (plugins), §4.18 (engine messages, if the agent finds a message list there), §5.4 (engagement), §5.9 (the `owner:`
  reason), §5.12 (logout and login), §7.4 (a new M38 row).
- **Decision Record Updates:** None. `docs/DECISIONS.md` has no open entries this design touches, and D-121 is already
  folded into §4.7.

---

### Guardrails for every phase

1. Follow `AGENTS.md`:
   - TDD: write the tests first and see them fail.
   - No spec section numbers or decision ids in comments, Kotlin or Starlark.
   - All Kotlin warnings are errors.
2. Do not raise `EngineInfo.API_VERSION` or `PluginApi.VERSION`. The engine is unreleased, so there are no migrations,
   no format-version bumps and no back-compat shims. A new payload field simply gets a default value.
3. Add CHANGELOG bullets to a new `### Pets and owners` subsection at the top of `## api_version 1 (unreleased)` in
   `docs/CHANGELOG.md`, above `### Groups and shared experience`. Create it in Phase 1A and have each later phase
   append. Two changes are behavior-breaking for scripts and need a before/after snippet: Phase 1A (`ownerOf` no longer
   reads `leader`, so a following mob is no longer a pet) and Phase 5 (an owner is drawn into its pet's fights).
4. In any phase that changes the script API, the plugin API or template-world content, regenerate the API files before
   the phase's final `./gradlew check` if any golden or API test fails:
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
   exactly as each phase says and nowhere else. Where a phase gives the meaning but not exact wording, write it in the
   style of the surrounding text and keep it short.
7. Locate code by the symbol names given, not by line numbers: the files drift. Where a phase says "mirror", open the
   named precedent first and copy its structure. Line numbers in parentheses are only hints.
8. Feature ownership (`script/src/main/kotlin/gups/script/Ownership.kt`). Symbols this milestone adds belong to these
   features, and nothing else is gated:
   - Kernel, with no `owns(...)` entry: `set_owner`, `clear_owner`, the actor view's `owner`, `on_owner_change`, `heel`,
     the `priority` arguments, the `owned_behaviors` rules export, `before_summon`, `on_summon`, `order`,
     `before_order`, the `ORDER` restriction point, and pet storage.
   - `Feature.COMBAT`: the `defend` behavior (as `"defend"`, beside `"aggressive", "assist", "flee"`), and the
     engagement coupling of Phase 5 (which is inside `startCombat` and `unlink`, already combat-only).
   - Record the same split in the §3.10 feature table: each phase that adds one of these symbols adds it to the kernel
     row or the `combat` row there, as its spec changes say.
9. Every new Starlark view class (a Kotlin class annotated `@StarlarkBuiltin`) must be added to `ScriptApi.VIEW_CLASSES`
   in `script/src/main/kotlin/gups/script/ApiModel.kt`. This milestone should need none; if a phase adds one anyway,
   register it.
10. Context budget. `docs/SPEC.md`, `Declarations.kt`, `Ctx.kt`, `Rules.kt` (script), `Views.kt`, `KernelCommands.kt`
    and `Codec.kt` are each long. Never read them whole. `grep -n` for the symbol or quoted text a step names, then read
    a window of about 80 lines around it.
11. In Starlark, every engine verb and query is a method on `ctx`. Write `ctx.set_owner(...)`, never a bare
    `set_owner(...)`. Behavior constructors (`heel()`, `defend()`, `follow(...)`) are top-level builtins.
12. A hook's "Key ctx fields" cell in §4.4 is exact (§4.4, "Context fields per hook"): set only the fields the row
    lists.

---

## Phase 1A: The `owner` field and ownership rules in core

### Objective & Scope

- **In Scope:**
  - `Mob.owner` and the rewrite of `World.ownerOf` to read only it.
  - `World.setOwner` / `World.clearOwner` / `assignOwner` with the `owner:<player_id>` active reason, spawn release, and
    ending fights with the owner's side.
  - `World.isOwnerSide` and `World.petsOf`.
  - The `ON_OWNER_CHANGE` hook name and its global dispatch (scripts can register it in Phase 1B).
  - `summon` setting `summonedBy` and the owner before `on_spawn`.
  - An interim quit rule: a quitting player's pets lose the `owner:` reason and stay where they are.
  - The CHANGELOG subsection.
- **Out of Scope:** *(the agent must not attempt any of these here)* the Starlark verbs, the view field, persistence,
  owned behaviors, `heel`, `defend`, `priority` arguments, the rules export, any plugin change, engagement coupling,
  `order`, `before_summon`/`on_summon`, and storing pets at logout.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Bubble.kt`: `setActive`, `clearActive`.
  - `core/src/main/kotlin/gups/core/world/Groups.kt`: `groupOf`, and how `on_group_change` is dispatched with
    `dispatchGlobal` (the precedent for a global hook).
  - `core/src/main/kotlin/gups/core/world/World.kt`: `removeActor` (its `spawnedBy` branch) and `fillSpawn`.
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: `stopCombat`, `opponentsOf`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Mob.owner`)
  - `core/src/main/kotlin/gups/core/world/Owners.kt` (new)
  - `core/src/main/kotlin/gups/core/world/Contributions.kt` (`ownerOf`)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`spawnMob`'s `prepare` parameter; `removeActor` calls
    `releaseSpawn`)
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`summon`)
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (`ON_OWNER_CHANGE`)
  - `core/src/main/kotlin/gups/core/world/Sessions.kt` (`quit`)
  - Tests: new `core/src/test/kotlin/gups/core/world/OwnersTest.kt`; update any existing core test that built a pet
    through `leader` (grep `leader =` in `core/src/test`).
  - `docs/SPEC.md`, `docs/CHANGELOG.md`.
- **Do Not Touch:** `script/`, `persist/`, `plugin-api/`, `Behaviors.kt`, `Actions.kt`, `template-world/`.

### Specification & Decision Record Changes

- §3.3 "Mob-specific state" bullet: after `summonedBy` insert "`owner` (the player the mob belongs to, saved)". Make
  clear in that bullet that `leader` is only whom a `follow` behavior follows.
- §3.3 Groups bullet "A pet resolves to its owner's group; a mob with no player owner has none.": add a sentence after
  it: "A mob's owner is its `owner` field, set by `set_owner` or by a player's `summon`."
- §5.9 "Simulated entities" bullet: replace "Pets and followers bonded to a player get the reason `bond:<player_id>`."
  with "A mob with an owner gets the reason `owner:<player_id>` while its owner is in the world."
- §4.3 `summon(label, ...)` primitive row: append "When the caster is an in-world player, each mob's owner is set to it
  (§4.5 `set_owner`). `summonedBy` and the owner are set before the mob's `on_spawn` runs."
- §3.10 feature table, kernel row: add "pet ownership (`set_owner`, `clear_owner`, `on_owner_change`)" after the
  `summon` verbs text.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Entities.kt, in class Mob, beside summonedBy
/** The player this mob belongs to, or null. Only Owners.kt writes it. */
var owner: EntityId? = null
  internal set

// Owners.kt (new, package gups.core.world)
internal fun ownerReason(ownerId: EntityId): String = "owner:${ownerId.v}"

/** Makes [owner] the owner of [mob], replacing any previous one. Returns whether anything changed. */
fun World.setOwner(mob: Mob, owner: Player): Boolean

/** Removes [mob]'s owner. Returns whether it had one. */
fun World.clearOwner(mob: Mob): Boolean

/** Sets the field, the reason, the spawn release and ends fights with the owner's side; fires no hook. */
internal fun World.assignOwner(mob: Mob, owner: Player)

/** For restoring saved state: sets the field only, and drops any saved `owner:` reason. */
fun World.restoreOwner(mob: Mob, id: EntityId?)

/** The in-world mobs whose owner is [p], ascending id, in any room. */
fun World.petsOf(p: Player): List<Mob>

/** True if [a] is [mob]'s owner, a member of the owner's group, or a pet of either. */
fun World.isOwnerSide(mob: Mob, a: Actor): Boolean

/** Takes [m] off its spawn rule so the zone respawns another; no-op when unbound. */
internal fun World.releaseSpawn(m: Mob)

// World.kt
fun spawnMob(label: Label, at: Room, index: Int = 0, prepare: (Mob) -> Unit = {}): Mob

// Hooks.kt, in enum HookName, after ON_GROUP_CHANGE
/** Global hook fired after a mob's owner is set or cleared. */
ON_OWNER_CHANGE("on_owner_change"),
```

- **Algorithmic Steps & Invariants:**
  1. `releaseSpawn(m)`: move the `spawnedBy` branch of `removeActor` into it (`s.live.remove(m.id)`, schedule
     `respawnAt` when null), then set `m.spawnedBy = null`. `removeActor` calls `releaseSpawn` instead of its inline
     code.
  2. `isOwnerSide(mob, a)`: `owner = ownerOf(mob) ?: return false`; true if `a === owner`, or `groupOf(owner)` contains
     `a`, or `ownerOf(a)` is `owner` or a member of `groupOf(owner)`.
  3. `assignOwner(mob, owner)`: `require(owner.inWorld)`; if the mob had another owner, `clearActive(mob,
     ownerReason(old))`; set `mob.owner = owner.id`; `setActive(mob, ownerReason(owner.id))`; `releaseSpawn(mob)`;
     `mob.dirty = true`. Then, for each `o` in `mob.fighting` resolved to actors, ascending id, with `isOwnerSide(mob,
     o)`: `stopCombat(mob, o)` (which fires `on_disengage` `"stop"` for whoever it frees).
  4. `setOwner(mob, owner)`: if `mob.owner == owner.id` return false. Remember the old owner id. `assignOwner`. If there
     was an old owner and `actor(oldId)` is a live actor, `dispatchGlobal(ON_OWNER_CHANGE, HookContext(actor = mob,
     target = old, event = "clear"))`. Then fire `"set"` with `target = owner`. Return true.
  5. `clearOwner(mob)`: if `mob.owner == null` return false; `clearActive(mob, ownerReason(old))`; `mob.owner = null`;
     `mob.dirty = true`; fire `"clear"` with the old owner as `target` when it is a live actor. Return true.
  6. `restoreOwner(mob, id)`: remove every `activeReasons` entry starting with `"owner:"`, then set `mob.owner = id`. No
     reason is added: at boot no owner is in the world. Phase 8 adds the reason when the owner logs in.
  7. `ownerOf(a)`: `val id = (a as? Mob)?.owner ?: return null`, then the existing in-world player check. Update its
     KDoc to "The in-world player that owns [a]; players and unowned mobs return null."
  8. `spawnMob(..., prepare)`: call `prepare(m)` right after `declareMob(m, proto)` and before `applyStartingStats`, so
     `starting_stats` and `on_spawn` both see it.
  9. `World.summon`: pass `prepare = { m -> m.summonedBy = caster.id; if (caster is Player && caster.inWorld) m.owner =
     caster.id }`. Set only the field in `prepare`: `setActive` calls `syncTimers` and marks the entity dirty, which
     should not run before the mob has entered the world. Right after `spawnMob` returns, if `m.owner != null` and the
     mob is still in the world, call `assignOwner(m, caster)` (it sees the same owner, so it only adds the reason and
     runs the rest of its steps). Remove the old `m.summonedBy = caster.id` line. After all `count` mobs are spawned and
     their `summoned` affects applied, fire `on_owner_change` `"set"` for each owned one, in ascending id.
  10. Charm order: an enemy charm spell engages its target when it resolves, before its effect runs (`SkillCommands.kt`,
      `if (hostile) w.startCombat(actor, each)` precedes the effect), so the effect's `set_owner` sees that engagement
      and ends it. Do not add any guard to `startCombat`: a pet may be engaged with its owner later.
  11. `quit` (interim): after `unlinkAll(player)`, for each `m in petsOf(player)`: `clearActive(m,
      ownerReason(player.id))`, keeping `owner`. Phase 8 replaces this with storage. Until then a pet left in the world
      stays dormant and unanchored after its owner quits, and does not regain the reason at login.
  12. Invariants: `owner` only names a `Player` id; right after gaining an owner, a mob is engaged with no one on its
      owner's side.

### Test & Documentation Requirements

- `OwnersTest.kt`:
  - set, replace (events `"clear"` then `"set"`, reasons swapped), clear, clear on an unowned mob returns false, set to
    the same owner returns false and fires nothing;
  - spawn release schedules a respawn;
  - `petsOf` order; `isOwnerSide` for owner, group member, a groupmate's pet, a stranger;
  - `ownerOf` ignores `leader` (a mob whose `leader` is a player and with no owner returns null);
  - **charm:** a mob engaged with a player is no longer engaged with it after `setOwner(mob, player)`, and
    `on_disengage` fired with `"stop"`;
  - after `setOwner`, `startCombat(pet, owner)` engages them (a pet can turn on its owner);
  - quit removes the reason but keeps `owner`;
  - `restoreOwner` drops a saved `owner:` reason.
- Summon: a player caster owns the summons, and a summoned mob's own `on_spawn` sees `summonedBy` and `owner` (use a
  test script host or a Kotlin hook stub, as existing core hook tests do); a mob caster owns nothing.
- Fix existing core tests that built a pet through `leader`: switch them to `setOwner`.
- CHANGELOG: create `### Pets and owners`; bullets for summon ownership and the breaking `ownerOf` change with
  before/after:

```python
# Before: a mob following a player counted as that player's pet in xp_share, group_of and allies_near.
# After: only a mob whose owner is set counts.
ctx.set_owner(wolf, player)
```

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :core:test --tests '*OwnersTest*'` passes.
- [] `grep -n "summonedBy ?: a.leader" -r core/src/main` returns nothing.
- [] `grep -n "bond:" docs/SPEC.md` returns nothing.

---

## Phase 1B: Ownership in Starlark and in saved state

### Objective & Scope

- **In Scope:**
  - The `set_owner` and `clear_owner` verbs, the actor view's `owner` field, and script registration of the global
    `on_owner_change` hook.
  - Saving and restoring `owner` in `MobPayload`. A restored owned mob is placed in the world as any mob is, through
    `restoreOwner`; Phase 8 holds it out instead.
- **Out of Scope:** *(the agent must not attempt any of these here)* everything listed out of scope for Phase 1A, and
  any core change other than calling the Phase 1A functions.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Owners.kt` (Phase 1A).
  - `script/src/main/kotlin/gups/script/Ctx.kt`: the `set_active` and `group_add` verbs (precedents for verb shape,
    `verb(...)`, `actorArg`).
  - `script/src/main/kotlin/gups/script/Declarations.kt`: how `on_group_change` is registered as a global hook (grep
    `ON_GROUP_CHANGE`).
  - `persist/src/test/kotlin/gups/persist/PersistenceTest.kt`: the `summonedBy` round-trip test.
- **Files to Modify / Create:**
  - `script/src/main/kotlin/gups/script/Ctx.kt`, `Views.kt`, `Declarations.kt`, `DocExamples.kt`
  - `persist/src/main/kotlin/gups/persist/Codec.kt` (`MobPayload.owner`, `encode`, `applyMob`)
  - Tests: a verb test beside the existing verb tests in `script/src/test/kotlin/gups/script/`; one round-trip test in
    `PersistenceTest.kt`; update any script or persist test that relied on `leader` meaning ownership.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files.
- **Do Not Touch:** `core/` (other than tests), `plugin-api/`, `SqlitePersistence.kt`, `template-world/`.

### Specification & Decision Record Changes

- §4.5 verbs table: add this row after `Simulation`:

```markdown
| Pets              | `set_owner(mob, player)`, `clear_owner(mob)` |
```

Pad the row to the table's column widths; `markdownFormat` will realign it.

- §4.5 verb semantics: add the bullet "`set_owner` makes `player`, an in-world player, the owner of `mob`, replacing any
  owner it had. The mob gets the active reason `owner:<player_id>` while its owner is in the world, leaves its spawn
  rule so the zone respawns another, and stops fighting its owner's side (the owner, the owner's group members, and a
  pet of any of them). `clear_owner` removes the owner and that reason, and does nothing on a mob without one. Each
  change fires the global `on_owner_change` hook; replacing an owner fires `"clear"` for the old one, then `"set"`. Both
  return whether anything changed."
- §4.5 `summon` bullet ("spawns `count` (1 or more) mobs..."): append "When `caster` is an in-world player, each mob's
  owner is set to it. `summonedBy` and the owner are set before the mob's `on_spawn` runs."
- §4.5 actor view fields: add `owner` (the owner's view, or `None`) next to the other actor fields (grep the §4.5 field
  list that names `level`).
- §4.4 hook table: add a row after `on_group_change`: `on_owner_change` | — | Global only (§4.9) | `actor`, `target`,
  `event` (`"set"` or `"clear"`). Add a paragraph after the `on_group_change` paragraph: "**`on_owner_change`** is a
  global hook (§4.9). It fires after each change of a mob's owner, once the change is complete. `ctx.actor` is the mob,
  `ctx.target` the owner gained or lost, and `ctx.event` is `"set"` or `"clear"`. A mob stored with its owner at logout
  and restored at login fires nothing. Its return value is ignored."
- §3.6 persistence: in the mob delta rules text, add `owner` beside `summonedBy`.

### Implementation Blueprint

- **Algorithmic Steps & Invariants:**
  1. Verbs in `Ctx.kt`, mirroring `set_active`: `set_owner(mob, player)` checks `mob` is a `Mob` ("set_owner(mob): must
     be a mob") and `player` an in-world `Player` ("set_owner(player): must be a player in the world"), calls
     `world.setOwner`, returns a Starlark bool. `clear_owner(mob)` likewise with `world.clearOwner`.
  2. Actor view (`Views.kt`): add `owner` (struct field) beside the other actor fields: the view of `world.ownerOf(a)`,
     or `None`. Using `ownerOf` makes an offline owner read `None`.
  3. Declarations: register `on_owner_change` as a global hook exactly as `on_group_change` is.
  4. Codec: `MobPayload` gains `val owner: Long? = null` after `summonedBy`; `encode` writes `e.owner?.v`; `applyMob`
     calls `world.restoreOwner(m, p.owner?.let(::EntityId))` after `restoreReasons` has run (so the saved `owner:`
     reason is dropped). Add `owner` to the Codec KDoc list beside `summonedBy`.

### Test & Documentation Requirements

- Script verb test: `ctx.set_owner`, `ctx.clear_owner`, both error messages, the view's `owner`, a global
  `on_owner_change` hook seeing `ctx.actor`, `ctx.target`, `ctx.event`.
- `PersistenceTest.kt`: `owner` round-trips, mirroring the `summonedBy` test; a saved `owner:` active reason is not
  restored.
- CHANGELOG bullets for the verbs, the view field and the hook. Note in the view-field bullet that `owner` is now an
  actor view field, so (per §3.9) it can no longer be a stat or vital name.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The new script and persist tests pass.

---

## Phase 2: Behavior priorities, `heel`, `defend`, and the default owned list

### Objective & Scope

- **In Scope:**
  - A `priority` field on every built-in behavior that has one, with the existing constants as defaults, and the
    `priority=` builtin argument.
  - The `Heel` and `Defend` behaviors and their builtins.
  - `Mob.ownedBehaviors` (transient cache), `World.behaviorsOf(mob)`, and the engine's default owned list.
  - The owner-side exclusion in `aggressive` and `assist`.
  - `think` and `guardDenial` reading `behaviorsOf`.
- **Out of Scope:** *(the agent must not attempt any of these here)* the `owned_behaviors` rules export, the plugin
  seam, engagement coupling, `order`, any persistence change.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Groups.kt` (`groupOf`).
  - `core/src/main/kotlin/gups/core/world/Owners.kt` (Phase 1A: `ownerOf` lives in `Contributions.kt`, `isOwnerSide` and
    `petsOf` in `Owners.kt`).
  - `script/src/main/kotlin/gups/script/Declarations.kt`: the `follow`, `assist`, `flee`, `wander` builtins and the
    behavior list near `"assist", ... "follow"` (around line 2290), and `BehaviorDecl`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Behaviors.kt`
  - `core/src/main/kotlin/gups/core/world/Actions.kt` (`think`)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`Mob.ownedBehaviors`)
  - `core/src/main/kotlin/gups/core/world/Owners.kt` (`refreshOwnedBehaviors`, `defaultOwnedBehaviors`; call it from
    `assignOwner`, null it in `clearOwner`)
  - `script/src/main/kotlin/gups/script/Declarations.kt`, `Ownership.kt`, `DocExamples.kt`
  - Tests: `core/src/test/kotlin/gups/core/world/BehaviorsTest.kt` (or the existing behaviors test file; find it with
    `grep -rln "Follow(" core/src/test`), `OwnersTest.kt`, and a declaration test for `priority=` in `script/src/test`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files.
- **Do Not Touch:** `Rules.kt` (either module), `plugin-api/`, `persist/`, `Combat.kt`.

### Specification & Decision Record Changes

- §4.7 table: change the header `Priority` to `Default priority`. Add `priority=<n>` to the signature of every row that
  has a number (`wander`, `aggressive`, `assist`, `flee`, `follow`, `pursue`, `scavenge`). Add rows:
  - `heel(priority=30)` | 30 | "Follows its owner by the shortest path, however far. Proposes nothing for a mob without
    an owner in the world."
  - `defend(group=True, priority=50)` | 50 | "Attacks the primary target of its owner, if the owner is in its room and
    engaged, else the owner's lowest-id opponent; with `group`, then the same for each in-room group member of the
    owner, in ascending id. `combat` feature."
- §4.7 bullets: add
  - "**Priority:** `priority` is any int; proposals sort by it, then by declaration order (§5.8)."
  - "**Owned mobs:** while a mob has an owner (§4.5 `set_owner`), it runs its owned behavior list instead of its
    prototype's `behaviors`. Unless the world's rules say otherwise (§4.6 `owned_behaviors`), that list is the
    prototype's `flee` and `sentinel` behaviors, in declaration order, then `heel()`, then `defend()` with `combat`.
    Whatever the list, an owned mob's `aggressive` and `assist` never pick its owner, its owner's group members, or a
    pet of any of them."
- §5.8 step 1: "Each behavior" becomes "Each behavior of the mob's current list (its prototype's, or its owned list
  while it has an owner)".
- §3.3 mob state: add "`ownedBehaviors`, the cached owned behavior list (transient)".
- §3.10 feature table: add `heel` and the `priority` arguments to the kernel row's behavior text if it lists behaviors,
  and `defend` to the `combat` row beside "the `aggressive`, `assist` and `flee` behaviors".

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Behaviors.kt: each of these data classes gains a trailing `val priority: Int = PRIORITY_X`
// and uses it in its Proposal instead of the constant:
// Wander, Aggressive, Assist, Flee, Follow, Pursue, Scavenge. Guard and Sentinel are unchanged.

const val PRIORITY_HEEL = 30
const val PRIORITY_DEFEND = 50

/** `heel()`: follows the mob's owner by the shortest path, however far. */
data class Heel(val priority: Int = PRIORITY_HEEL) : Behavior

/** `defend(group)`: joins the fights of the mob's owner, and with [group] its in-room group members. */
data class Defend(val group: Boolean = true, val priority: Int = PRIORITY_DEFEND) : Behavior

/** The behaviors [mob] runs now: its owned list while it has an owner, else its prototype's. */
fun World.behaviorsOf(mob: Mob): List<Behavior>

// Entities.kt, class Mob
/** The owned behavior list, cached while the mob has an owner; never saved. */
var ownedBehaviors: List<Behavior>? = null
  internal set

// Owners.kt
internal fun World.defaultOwnedBehaviors(mob: Mob): List<Behavior>
/** Recomputes and caches [mob]'s owned list; no-op without an owner. */
internal fun World.refreshOwnedBehaviors(mob: Mob)
```

- **Algorithmic Steps & Invariants:**
  1. `Heel.propose`: null if engaged. `owner = ownerOf(mob) ?: return null`. If `owner.room === mob.room` return null.
     `path = pathTo(mob.room, owner.room)`; null or empty → null. Propose the first step with `priority`, source
     `"heel"`, `MoveAction(path.first())`, mirroring `Follow`.
  2. `Defend.propose`: null unless `Feature.COMBAT` is on and the mob is not engaged. `owner = ownerOf(mob)`. Candidates
     in order: the owner (if in `mob.room`), then if `group`, `groupOf(owner)`'s members other than the owner, in
     `mob.room`, ascending id. For the first candidate that is engaged: its opponents from `opponentsOf`, filtered by
     `!isOwnerSide(mob, it) && it !== mob`; pick the one whose id is the candidate's `primaryTarget`, else the first.
     Propose `"${commands.nameOf("kill")} ${foe.keywords.first()}"` with `KillAction(foe)`, mirroring `Assist`.
  3. `isOwnerSide` already exists in `Owners.kt` (Phase 1A); use it, do not redefine it.
  4. `Aggressive` and `Assist`: when the mob has an owner, skip candidates (and, in `Assist`, foes) for which
     `isOwnerSide(mob, it)` is true. Leave their behavior for unowned mobs byte-for-byte the same.
  5. `behaviorsOf(mob)`: `if (mob.owner != null) mob.ownedBehaviors ?: defaultOwnedBehaviors(mob) else
     mob.proto.behaviors`.
  6. `defaultOwnedBehaviors(mob)`: `mob.proto.behaviors.filter { it is Flee || it == Sentinel } + Heel() + (if (COMBAT
     in features) listOf(Defend()) else emptyList())`. Check how `Sentinel` is declared (an `object` or a class) and
     match it accordingly.
  7. `refreshOwnedBehaviors(mob)`: in this phase, `mob.ownedBehaviors = if (mob.owner == null) null else
     defaultOwnedBehaviors(mob)`. Phase 3 and 4 extend it. Call it at the end of `assignOwner` and `restoreOwner`;
     `clearOwner` sets `ownedBehaviors = null`.
  8. `think` (`Actions.kt`): replace `mob.proto.behaviors` with `behaviorsOf(mob)` (both uses, including the `Sentinel
     in behaviors` check). `guardDenial` (`Behaviors.kt`): replace `m.proto.behaviors` with `behaviorsOf(m)`.
  9. Builtins (`Declarations.kt`): add an optional `priority` param (default `None` → the constant) to each builtin of
     step "Signatures"; it must be an int. Add `heel(priority=None)` and `defend(group=True, priority=None)` builtins
     mirroring `follow`, and add both names to the behavior-name list near line 2290. `defend` needs `combat` (use
     `need(...)` as `assist` does); register `owns(Feature.COMBAT, ..., "defend")`.
  10. Value equality: the new `priority` field takes part in `equals`, so the version-bump lint sees a priority change.
      Make sure the lint's behavior comparison tests still pass.

### Test & Documentation Requirements

- Each behavior honors a custom `priority` (two behaviors reordered by it).
- `heel`: follows an owner 5 rooms away (beyond `BUBBLE_RADIUS`), does nothing when engaged, does nothing unowned.
- `defend`: assists the owner's primary target; with `group`, a group member's fight; never picks an owner-side actor;
  needs `combat`.
- An owned mob with `wander` and `aggressive("players")` in its prototype neither wanders nor attacks its owner; after
  `clearOwner` it does again.
- `sentinel` kept in the owned list stops `heel` (a "stay" pet).
- Declarations: `priority=` accepted, non-int rejected; `defend` without `combat` rejected.
- CHANGELOG: bullets for `priority=`, `heel`, `defend`, and the owned list.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] Behavior tests above pass.
- [] `grep -n "proto.behaviors" core/src/main/kotlin/gups/core/world/Actions.kt` returns nothing.

---

## Phase 3: The `owned_behaviors` rules export

### Objective & Scope

- **In Scope:**
  - The optional rules export `owned_behaviors(ctx)` and the rules ctx field `behaviors`.
  - Allowing behavior builtins to be called while that export runs.
  - Recomputing owned lists after a reload.
  - The load-time dry run of the export.
- **Out of Scope:** *(the agent must not attempt any of these here)* the plugin seam, engagement, `order`, persistence.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/rules/` (grep `fun xpShare`): the core rules interface and `RulesContext` (grep
    `class RulesContext`; see how `contributors` was added in M37).
  - `script/src/main/kotlin/gups/script/Rules.kt`: `XP_SHARE` in `enum RulesExport`, `override fun xpShare`, the ctx
    fields `xp` and `contributors` (around line 190), the optional-export handling (grep `XP_SHARE` for every place the
    export is special-cased as optional), and the dry run (grep `A second xp_share contributor`).
  - `script/src/main/kotlin/gups/script/Declarations.kt`: `contentCtx`, `loadCtx`.
  - The reload entry point: grep `fun World.reload` in `core/src/main`.
- **Files to Modify / Create:**
  - the core rules interface file and `RulesContext` (in `core/src/main/kotlin/gups/core/rules/`)
  - `script/src/main/kotlin/gups/script/Rules.kt`
  - `script/src/main/kotlin/gups/script/Declarations.kt` (behavior builtins usable in the export)
  - `core/src/main/kotlin/gups/core/world/Owners.kt` (`refreshOwnedBehaviors` uses the export)
  - the reload code (call `refreshOwnedBehaviors` for every owned in-world mob after a successful reload)
  - Tests in `script/src/test/kotlin/gups/script/` (mirror the `xp_share` tests), plus a reload test.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files.
- **Do Not Touch:** `plugin-api/`, `persist/`, `Combat.kt`, `KernelCommands.kt`.

### Specification & Decision Record Changes

- §4.6 intro: replace "The one exception is `xp_share`, which a world may leave out: the engine's default pays the
  killer the whole pool." with "Two exports are optional: `xp_share`, whose default pays the killer the whole pool, and
  `owned_behaviors`, whose default is described in §4.7."
- §4.6 rules ctx paragraph: add "`behaviors` (in `owned_behaviors`, the mob's prototype behaviors as behavior values;
  `None` elsewhere)".
- §4.6 table: add row `owned_behaviors(ctx)` | kernel | "List of behaviors that `ctx.actor`, a mob, runs while
  `ctx.target` owns it." Add a paragraph after the `xp_share` paragraph: "**`owned_behaviors`**: called when a mob gains
  an owner, when it is restored beside its owner at login, and for every owned mob after a reload. The result is cached
  on the mob and not saved. Behavior constructors (§4.7) may be called inside it and nowhere else at run time. A rules
  error keeps the default list. Plugins then adjust the result (§4.15). When defined, the load-time dry run calls it
  with the throwaway mob and a player, and checks it returns a list of behaviors."
- §4.7 "Owned mobs" bullet (Phase 2): "Unless the world's rules say otherwise" already points here; no change.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// core rules interface, beside xpShare
/** The owned behavior list for c.actor (a mob) owned by c.target, or null for the engine default. */
fun ownedBehaviors(c: RulesContext): List<Behavior>? = null

// RulesContext: a new trailing constructor parameter
val behaviors: List<Behavior>? = null

// script Rules.kt
// enum RulesExport: OWNED_BEHAVIORS("owned_behaviors"), optional like XP_SHARE.
override fun ownedBehaviors(c: RulesContext): List<Behavior>?
```

- **Algorithmic Steps & Invariants:**
  1. Mark `OWNED_BEHAVIORS` optional everywhere `XP_SHARE` is treated as optional (the missing-export check and the
     "feature" check: it is kernel).
  2. `ownedBehaviors(c)`: `run(RulesExport.OWNED_BEHAVIORS, c, ctxArgs(c)) { v -> ... }`. Convert: `v` must be a
     Starlark list; each element must be a `BehaviorDecl` ("rules.owned_behaviors return value: elements must be
     behaviors, got <type>"); return `elements.map { it.behavior }`. Return null (default) when the export is not
     defined or on a rules error, as `run` does.
  3. Rules ctx `behaviors` field: a list of `BehaviorDecl(b)` for `c.behaviors`, `None` otherwise. Mirror how
     `contributors` is exposed.
  4. Builtins at run time: add `private fun behaviorCtx(thread: StarlarkThread, fn: String): String` returning the
     package path for label patterns. It returns `contentCtx(thread, fn).pkg.path` while loading; otherwise, if the
     thread carries an owned-behaviors marker, the rules package path; otherwise it fails with "`<fn>()` can only be
     used in a package's .star files or in rules.owned_behaviors". Set the marker in `ownedBehaviors` around the `run`
     call, using the same thread-local mechanism `loadCtx` uses (`thread.getThreadLocal(LoadCtx::class.java)`; set your
     own marker class the same way). Switch every behavior builtin from `contentCtx` to `behaviorCtx`. The builtins read
     only `pkg.path` from `LoadCtx` (for `actorFilter`'s `:`-relative patterns); `loadCtx` also calls `tick()`, which
     the rules run's own step limit replaces. `need(...)` and the schema (for `aggressive(via)` channels) come from the
     `Declarations` instance, not `LoadCtx`: confirm the rules package's predeclared builtins are that same instance
     (grep where `Declarations(` is constructed) before relying on it, and if they are not, make the marker carry what
     `need` and `channels` read. A label pattern without a package then means the rules package, which matches §4.7's
     "`:goblin_*` means this package".
  5. `refreshOwnedBehaviors(mob)`: `val owner = ownerOf(mob)`; if `mob.owner == null` → null; else
     `rules.ownedBehaviors(RulesContext(this, mob, owner, behaviors = mob.proto.behaviors)) ?:
     defaultOwnedBehaviors(mob)`. With an owner not in the world (restore before login), pass `target = null`; the
     export must cope with `ctx.target == None`.
  6. Reload: after a successful reload, for each in-world `Mob` with `owner != null`, `refreshOwnedBehaviors`.
  7. Dry run: if defined, call it with the dry-run mob and one of the dry-run players as `target`, mirroring the
     `xp_share` dry run, and fail the load on a non-list result.

### Test & Documentation Requirements

- A rules package defining `owned_behaviors` that returns `[heel(priority = 70), flee(below = vitals(hp = 10))]`: an
  owned mob uses it; `ctx.behaviors` lists the prototype behaviors; `ctx.target` is the owner.
- A rules error (returning an int) falls back to the default and records the rules diagnostic.
- Calling `heel()` from an ordinary hook fails with the new message.
- After a reload that changes the export, an owned mob's list changes.
- The world without the export loads (optional).
- CHANGELOG bullet for `owned_behaviors` and `ctx.behaviors`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] The tests above pass; existing `xp_share` tests still pass.

---

## Phase 4: The `addOwnedBehaviors` plugin seam

### Objective & Scope

- **In Scope:**
  - Plain behavior descriptors (both directions) in core.
  - `PluginRegistrar.addOwnedBehaviors(doc, adjust)` and its call type; host wiring into the world; plugin docs listing.
  - `refreshOwnedBehaviors` running the adjusters after the rules export.
- **Out of Scope:** *(the agent must not attempt any of these here)* engagement, `order`, persistence, any other plugin
  seam.

### Context Working Set

- **Read / Reference Only:**
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`: `addContainmentFilter`, `PluginContainmentCall`,
    `addHook`, `PluginHookCall` (plain-value conventions: ints as `Long`, entities as views).
  - `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`: `addContainmentFilter`, where `allFilters` is
    assigned to `world.containmentFilters` (around line 568), the `PluginFilterInfo` listing (around line 724), and how
    a throwing handler is turned into a `plugin` diagnostic.
  - `core/src/main/kotlin/gups/core/world/Holdings.kt`: `fun interface ContainmentFilter` (core-side precedent);
    `World.containmentFilters` in `World.kt`.
  - `cli/src/main/kotlin/gups/cli/Commands.kt` (around line 861, `containmentFilters = ...PluginFilterDocSpec`).
  - `samples/curse-plugin/src/test/kotlin/gups/sample/curse/CursePluginTest.kt` (a fake registrar listing every seam; it
    must compile after the interface grows).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/BehaviorDescriptors.kt` (new)
  - `core/src/main/kotlin/gups/core/world/World.kt` (`ownedBehaviorAdjusters`)
  - `core/src/main/kotlin/gups/core/world/Owners.kt`
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt`,
    `plugin-api/src/main/kotlin/gups/pluginapi/host/PluginHost.kt`
  - `cli/src/main/kotlin/gups/cli/Commands.kt` and the plugin docs renderer if it lists seams
  - every fake `PluginRegistrar` implementation in tests and samples (find them with `grep -rln "addContainmentFilter"
    --include='*.kt' .` excluding `build/`)
  - Tests: new `core/src/test/kotlin/gups/core/world/BehaviorDescriptorsTest.kt`; a host test beside the existing
    `PluginHost` tests in `plugin-api/src/test/`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, `docs/PLUGINS.md` (if it lists seams), regenerated API files.
- **Do Not Touch:** `script/` Rules or Ctx, `persist/`, `Combat.kt`, `KernelCommands.kt`.

### Specification & Decision Record Changes

- §4.15, after the "Containment filters" bullet, add: "**Owned behaviors (`addOwnedBehaviors`):** adjusts the owned
  behavior list of a mob (§4.7) after the world's `owned_behaviors` rules export, in plugin-id order. A handler gets a
  read-only `PluginWorld`, the mob, the owner (or null while the owner is offline) and the list so far as descriptors,
  and returns the new list. A descriptor is a map with `kind` (the behavior's builtin name) and its builtin arguments by
  name: ints as `Long`, `toward`/`allies`/`target` as a list of strings (label patterns must be absolute,
  `//pkg:name*`), `below` as a map of vital names to `Long`, `exit` as a direction name. An invalid descriptor, or a
  handler that throws, records a `plugin` diagnostic and leaves the list unchanged."
- §4.15 seam summary sentence (grep "containment filters (`addContainmentFilter`)"): add "owned behaviors
  (`addOwnedBehaviors`)".

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// core BehaviorDescriptors.kt
/** The behavior as a plain map: "kind" plus its builtin arguments by name. */
fun Behavior.descriptor(): Map<String, Any?>
/** Builds a behavior from [d]; throws IllegalArgumentException naming the problem. */
fun World.behaviorOf(d: Map<String, Any?>): Behavior

// core World.kt (or Owners.kt), mirroring ContainmentFilter
fun interface OwnedBehaviorAdjuster {
  fun adjust(world: World, mob: Mob, owner: Player?, behaviors: List<Behavior>): List<Behavior>
}
var ownedBehaviorAdjusters: List<OwnedBehaviorAdjuster> = emptyList() // in class World

// plugin-api PluginApi.kt
fun addOwnedBehaviors(doc: String, adjust: (PluginOwnedBehaviorsCall) -> List<Map<String, Any?>>)

data class PluginOwnedBehaviorsCall(
  val world: PluginWorld,
  val mob: PluginActor,
  val owner: PluginActor?,
  val behaviors: List<Map<String, Any?>>,
)
```

- **Algorithmic Steps & Invariants:**
  1. Descriptors cover every built-in behavior: `wander` (`radius`, `chance`, `priority`), `aggressive` (`toward`,
     `unless_level_above`, `unless_level_below`, `chance`, `via`, `priority`), `assist` (`allies`, `priority`), `flee`
     (`below`, `priority`), `guard` (`exit`, `allies`), `follow` (`target`, `priority`), `pursue` (`target`,
     `priority`), `sentinel` (none), `scavenge` (`priority`), `heel` (`priority`), `defend` (`group`, `priority`). Use
     the exact builtin parameter names from `Declarations.kt`. `behaviorOf(descriptor())` must equal the original for
     every behavior (round-trip test).
  2. `behaviorOf` checks `kind`, unknown keys, types, and feature needs (`defend`, `aggressive`, `assist`, `flee` need
     `combat`); a relative label pattern is rejected.
  3. Host: store adjusters like `filters`; on world start assign `world.ownedBehaviorAdjusters` as `allFilters` is
     assigned. Each adapter converts the list to descriptors, calls the plugin, converts back with `behaviorOf`; any
     exception → `plugin` diagnostic naming the plugin, and the input list is returned unchanged.
  4. `refreshOwnedBehaviors`: after the rules/default step, `fold` the list through `ownedBehaviorAdjusters` in order.
  5. Docs listing: mirror `PluginFilterInfo`/`PluginFilterDocSpec` with an owned-behaviors entry carrying `doc`.

### Test & Documentation Requirements

- Descriptor round trip for all eleven behaviors; rejection cases (unknown kind, unknown key, wrong type, relative
  pattern, `defend` without combat).
- A plugin raising `heel`'s priority to 80 changes the owned list; two plugins apply in plugin-id order; a throwing
  plugin leaves the list unchanged and records a diagnostic.
- CHANGELOG bullet (plugin API section of the subsection) and `docs/PLUGINS.md` if it enumerates seams.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings, including the sample plugins.
- [] Descriptor and host tests pass.

---

## Phase 5: Owner engagement follows the pet; pet disengagement follows the owner

### Objective & Scope

- **In Scope:** the two coupling rules inside `startCombat` and `unlink`, and their `on_disengage` events.
- **Out of Scope:** *(the agent must not attempt any of these here)* `order`, hooks for summons, persistence, any change
  to flee or movement code beyond what the coupling needs (it should need none).

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Movement.kt` (the `unlinkAll` / `fireDisengage` pairs around lines 31 and 84),
    `Sessions.kt` (`quit`), `Combat.kt` death settlement (`unlinkAll(victim) - victim`): callers that must keep working
    unchanged.
  - `core/src/main/kotlin/gups/core/world/Contributions.kt` (`ownerOf`) and `Owners.kt` (`isOwnerSide`, `petsOf`).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`startCombat`, `unlink`)
  - Tests: new `core/src/test/kotlin/gups/core/world/PetCombatTest.kt`
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `Movement.kt`, `Sessions.kt`, `Behaviors.kt`, `script/`, `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- §5.4 Engagement, after "Engagement is always symmetric. ..." add:
  - "**Pets and owners.** When a mob with an owner becomes engaged with an actor, its owner is engaged with that actor
    too, if the owner is in the same room and the actor is not the owner itself. Players, other players' pets and the
    owner's own group members count like anyone else, so a player can't attack anyone through a pet without taking part
    in the fight; when two players' pets fight, both owners are drawn in and so fight each other. The owner then
    auto-attacks like any engaged actor; a world that doesn't want that vetoes the owner's swings in `before_attack` or
    its rules."
  - "When an owner's engagement with an opponent ends, whatever the cause, each of its pets in that room engaged with
    that opponent is disengaged from it too, and gets `on_disengage` with the owner's event. A pet a world lets start
    fights on its own (by keeping `aggressive` in its owned list, §4.7) fights without its owner. A pet attacked while
    its owner is away fights back as any actor does."
- §3.10 feature table, `combat` row: add "owner and pet engagement coupling".

### Implementation Blueprint

- **Signatures & Types:** no new public API. Private helper in `Combat.kt`:

```kotlin
/** [owner]'s pets in [room] engaged with [foe], ascending id. */
private fun World.petsEngagedWith(owner: Player, room: Room, foe: Actor): List<Mob>
```

- **Algorithmic Steps & Invariants:**
  1. `startCombat(a, b)`: after the existing body, when `fresh`, for each pair `(pet, foe)` in `[(a, b), (b, a)]`: if
     `pet is Mob`, `val owner = ownerOf(pet)`, and `owner != null && owner.room === pet.room && owner !== foe`, call
     `startCombat(owner, foe)`. There is deliberately no side or player check. The recursion terminates: each step
     engages a `Player` owner, which is never owned itself, so it goes at most two levels deep (pet 1 vs pet 2 → owner 1
     vs pet 2 → owner 2 vs owner 1). `startCombat` already ignores dying actors and repeat engagements.
  2. `unlink(a, b)`: after the existing edits and before building the return list, collect extra freed actors: for each
     `(owner, foe)` in `[(a, b), (b, a)]` where `owner is Player`, for each `pet in petsEngagedWith(owner, owner.room,
     foe)`, call `unlink(pet, foe)` recursively and add what it frees. Return the combined list in order: the original
     two, then the pets' freed actors, without duplicates (`foe` may be freed by a pet's unlink rather than the owner's;
     keep the first occurrence).
  3. `unlinkAll(a)` needs no change: it calls `unlink`, so a fleeing or quitting owner's pets are freed in the same
     list, and the caller's single `fireDisengage(freed, reason)` gives them the owner's event (`"flee"`, `"stop"` or
     `"death"`).
  4. Death of the opponent: `unlinkAll(victim)` already frees every pet; the coupling adds nothing new there. Death of
     the owner: its pets leave the fight with the owner's killer, which is intended.
  5. `petsEngagedWith`: `room.actorIndex.values.filterIsInstance<Mob>().filter { it.owner == owner.id && foe.id in
     it.fighting }.sortedBy { it.id }`.

### Test & Documentation Requirements

- `PetCombatTest.kt` (mirror the fixtures of existing combat tests in `core/src/test`):
  - A pet engaging a mob engages the owner in the same room; the victim's primary target stays the pet.
  - An owner in another room is not engaged.
  - A pet engaging a member of the owner's group engages the owner with that member.
  - A pet attacking another player engages its owner with that player (no risk-free player killing through a pet).
  - Player 1's pet fighting player 2's pet, both owners present: owner 1 is engaged with pet 2, owner 2 with pet 1, and
    the two owners with each other.
  - A pet attacking its own owner does not loop and does not engage the owner with itself.
  - **Exploit:** pet and owner engaged with a goblin; owner flees → the pet is no longer engaged with the goblin, and
    its `on_disengage` sees `"flee"`. Same for owner `stop_combat` (`"stop"`) and owner walking out.
  - Owner dies → pets disengage.
  - A pet attacked while its owner is in another room stays engaged and fights.
- CHANGELOG: breaking bullet with before/after:

```python
# Before: ordering or letting a pet fight left its owner out of the fight.
# After: the owner is engaged with whatever its pet fights in the same room,
# and the pet leaves the fight when its owner does.
```

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :core:test --tests '*PetCombatTest*'` passes, and all existing combat and flee tests still pass.

---

## Phase 6: The `order` command, `before_order`, and the `ORDER` restriction point

### Objective & Scope

- **In Scope:** the kernel `order` command, its engine messages, the `before_order` hook, the `ORDER` restriction point
  in core and plugin API, and the re-check of an ordered action when it runs.
- **Out of Scope:** *(the agent must not attempt any of these here)* `stay`/`heel` commands, a GMCP package for pets,
  persistence, summon hooks.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`: a `FastCommand` entry (`say`), the `RestrictionPoint.PUT`
    check (around line 1603) as the restriction precedent, and how a command finds an actor by keyword in the room.
  - `core/src/main/kotlin/gups/core/world/Commands.kt`: `World.act` / `submit`.
  - `core/src/main/kotlin/gups/core/model/Entities.kt`: `class QueuedAction`, `Actor.actionQueue`.
  - `core/src/main/kotlin/gups/core/world/Actions.kt`: `runQueued`; `World.kt`: where it is called (`runQueued(a,
    a.actionQueue.removeFirst())`).
  - `core/src/main/kotlin/gups/core/model/Creation.kt`: `enum RestrictionPoint`, `RestrictionRequest`.
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt`: how a message key and its default text are declared.
  - `core/src/main/kotlin/gups/core/world/Hooks.kt`: `BEFORE_CAST` and its `vetoable` handling; `World.dispatch`.
  - `script/src/main/kotlin/gups/script/Declarations.kt`: how the hook catalog lists attach points (grep `BEFORE_CAST`).
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/KernelCommands.kt`, `KernelMessages.kt`, `Hooks.kt`, `Actions.kt`
    (`runQueued`)
  - `core/src/main/kotlin/gups/core/model/Entities.kt` (`QueuedAction.orderedBy`, `CastState.orderedBy`)
  - `core/src/main/kotlin/gups/core/world/Combat.kt`: only the `CastState(...)` copy that resumes a held cast (grep
    `CastState(c.action`), which must carry `orderedBy` over
  - `core/src/main/kotlin/gups/core/model/Creation.kt` (`RestrictionPoint.ORDER`)
  - `plugin-api/src/main/kotlin/gups/pluginapi/PluginApi.kt` (`PluginRestrictionPoint.ORDER`) and wherever the host maps
    plugin points to core points
  - `script/src/main/kotlin/gups/script/Declarations.kt` (hook catalog entry), `DocExamples.kt` if required
  - Tests: new `core/src/test/kotlin/gups/core/world/OrderCommandTest.kt`; a plugin restriction test beside the existing
    ones.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files.
- **Do Not Touch:** `Combat.kt` (except the line above), `Behaviors.kt`, `persist/`.

### Specification & Decision Record Changes

- §4.9 fast-lane list: add `order`.
- §4.9 bullets, add: "**`order <pet> <command>`** queues `<command>` on one of the player's pets in the room, found by
  keyword, as `act` does (§4.5): the pet pays its own warmup and lag, and the player pays none. `order pets <command>`
  orders each of the player's pets in the room, in ascending id. A pet must be simulated and able to act. Each order is
  checked by the `ORDER` restriction point (§4.15), then `before_order`; a refusal tells the player its message and
  leaves that pet alone. A queued order keeps the pet from thinking until it is done. An ordered action is checked again
  when it runs: if the pet's owner is no longer in the pet's room, or no longer owns it, the action is dropped and the
  owner, if in the world, is told. So a pet can't be sent to fight where its owner isn't."
- §3.10 feature table, kernel row: add "the `order` command, `before_order` and the `ORDER` restriction point".
- §4.4 hook table: add row `before_order` | ✓ | Mob (the pet), Room | `actor` (the owner), `target` (the pet), `text`
  (the command line).
- §4.15 restrictions bullet: add `ORDER` to the list of points.
- §4.18: if it enumerates engine message keys, add the new ones.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Hooks.kt
/** Vetoable hook evaluated before a pet is given an order. */
BEFORE_ORDER("before_order"),

// Creation.kt, enum RestrictionPoint
/** A player giving an order to one of its pets; actor is the owner, target the pet. */
ORDER,

// KernelMessages.kt (names may follow the file's convention)
ORDER_USAGE   // "Order whom to do what?"
ORDER_NO_PET  // "You have no pet here called {name}."
ORDER_NO_PETS // "You have no pets here."
ORDER_GIVEN   // "You order {pet} to '{command}'."
ORDER_CANT    // "{pet} can't do that right now."
ORDER_DROPPED // "{pet} hesitates without you nearby."

// Entities.kt, class QueuedAction: a new mutable property (not a constructor parameter)
/** The owner who ordered this action, or null when the actor chose it itself. */
var orderedBy: EntityId? = null
```

- **Algorithmic Steps & Invariants:**
  1. Register `FastCommand("order", category = <the category of group or social commands; pick the closest existing>,
     help = "Give an order to your pet.\nUsage: order <pet|pets> <command>")`.
  2. Parse the first word as the pet keyword and the rest as the command; empty either → `ORDER_USAGE`.
  3. Pets = `room.actorIndex.values.filterIsInstance<Mob>().filter { it.owner == player.id && it.inWorld }`, ascending
     id. For `pets`, all of them (none → `ORDER_NO_PETS`); else the first matching the keyword, matching keywords the
     way other room lookups do (none → `ORDER_NO_PET`).
  4. For each pet: skip with `ORDER_CANT` if `!canAct(pet) || !isSimulated(pet)`. Check the `ORDER` restriction with
     `RestrictionRequest(point = ORDER, actor = player, target = pet)` (add a `text` field to the request only if the
     type has a free-text slot; otherwise the restriction sees actor and target only). Then `dispatch(BEFORE_ORDER, pet,
     pet.room, HookContext(actor = player, target = pet, text = command))`. A denial sends its message. Otherwise note
     `val before = pet.actionQueue.size`, call `act(pet, command)`, and set `orderedBy = player.id` on every entry from
     index `before` to the end of `pet.actionQueue` (a fast-lane line runs at once and queues nothing; that is fine,
     since the owner is present). `act` true → `ORDER_GIVEN`, false → `ORDER_CANT`. `RestrictionRequest.target` is
     `Entity?`, so `target = pet` fits; add no new field.
  5. Re-check at run time: at the top of `runQueued(actor, queued)`, if `queued.orderedBy != null`, let `owner =
     actor(queued.orderedBy)`; if `actor !is Mob || actor.owner != queued.orderedBy || owner == null || !owner.inWorld
     || owner.room !== actor.room`, drop the action: tell the owner `ORDER_DROPPED` if it is an in-world player, and
     return without running it. Nothing else changes for unordered actions.
  6. Casts: an enemy spell engages its target only when it resolves, after warmup, so the check must also run then. Add
     `val orderedBy: EntityId? = null` as a trailing `CastState` constructor parameter. Pass `queued.orderedBy` from
     `runQueued` into `execute` (a new defaulted parameter) and from there into the `CastState` it creates. In
     `advanceCast`, before `validate`, apply the same owner test as step 5 to `cast.orderedBy`; on failure tell the
     owner `ORDER_DROPPED`, set `actor.waitState = cast.action.lag / 2` (as a failed validation does) and return.
  7. Map `PluginRestrictionPoint.ORDER` to `RestrictionPoint.ORDER` wherever the other points are mapped; no feature
     requirement.
  8. Hook catalog: `before_order` attaches to Mob and Room, vetoable.

### Test & Documentation Requirements

- `order wolf kill goblin`: the pet's queue holds the kill, the player's queue and `waitState` are untouched; combined
  with Phase 5 the owner becomes engaged when the pet's attack lands.
- `order pets ...` orders two pets in id order; a pet in another room is not ordered; another player's pet can't be
  ordered (`ORDER_NO_PET`).
- `before_order` returning `ctx.deny("The wolf ignores you.")` blocks it; a plugin `ORDER` restriction blocks it.
- An incapacitated pet gets `ORDER_CANT`.
- **Exploit, sequence 1:** give the wolf some recovery (`waitState`) so the order waits in its queue, `order wolf kill
  goblin`, then the owner walks out before it runs → the kill is dropped, the wolf is not engaged, the owner hears
  `ORDER_DROPPED`.
- **Exploit, sequence 2:** `order wolf north`, then `order wolf kill goblin` → the move runs (owner present), the kill
  is dropped because the owner is no longer in the wolf's room.
- **Exploit, sequence 3:** `order wolf cast <an enemy spell with warmup> goblin`, then the owner leaves during the
  warmup → the cast does not resolve, and the goblin is not engaged with the wolf.
- A pet's own think-chosen action (no `orderedBy`) is unaffected by its owner's absence.
- CHANGELOG bullets for `order`, `before_order`, `ORDER`.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :core:test --tests '*OrderCommandTest*'` passes.

---

## Phase 7: `before_summon` and `on_summon`

### Objective & Scope

- **In Scope:** the two hooks, the hook-context fields they need, and the ctx fields `label` and `count`, for both the
  `summon` effect primitive and the `ctx.summon` verb.
- **Out of Scope:** *(the agent must not attempt any of these here)* persistence, any change to owner or behavior code.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/SkillCommands.kt`: `veto` with `BEFORE_CAST` (dispatch precedent).
  - `script/src/main/kotlin/gups/script/Ctx.kt`: the `targets` field (around line 191) and `extras.targets`, the
    `summon` verb.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/Combat.kt` (`summon`)
  - `core/src/main/kotlin/gups/core/world/Hooks.kt` (hook names; `HookContext.summonLabel`, `count`, `mobs`)
  - `script/src/main/kotlin/gups/script/Ctx.kt` (fields `label`, `count`; `targets` falls back to `hc.mobs`; the verb's
    return on a veto)
  - `script/src/main/kotlin/gups/script/Declarations.kt` (catalog entries)
  - Tests: `core/src/test/kotlin/gups/core/world/SummonHooksTest.kt` (new); a script test beside
    `AffectModifierScriptTest.kt`'s summon test.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`, regenerated API files.
- **Do Not Touch:** `Owners.kt` (other than calling it), `persist/`, `plugin-api/`.

### Specification & Decision Record Changes

- §4.4 table: rows
  - `before_summon` / `on_summon` | ✓ / — | Room, Mob (observer) | `actor` (the caster), `label`, `count` / `actor`,
    `targets` (the summoned mobs).
- §4.4 paragraph: "**`before_summon`** fires before any summon, from the `summon` primitive or the `summon` verb, in the
  caster's room. A veto spawns nothing: the caster is told the message and the verb returns `[]`. **`on_summon`** fires
  once every mob of the summon has spawned and any `on_owner_change` has fired, with `ctx.targets` the new mobs in
  order; the new mobs are in the room, so they see it as observers too."
- §4.5 ctx fields: add `label` (the summoned prototype's label, in `before_summon`) and `count` (in `before_summon`);
  `targets` also lists the summoned mobs in `on_summon`.
- §4.3 `summon` primitive row and §4.5 `summon` bullet: append "Observers can veto it with `before_summon` (§4.4)."
- §3.10 feature table, kernel row: add `before_summon` and `on_summon` beside the `summon` verb.

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// Hooks.kt, enum HookName
BEFORE_SUMMON("before_summon"),
ON_SUMMON("on_summon"),

// HookContext: new trailing fields
/** The prototype being summoned, for before_summon. */
val summonLabel: Label? = null,
/** How many mobs are being summoned, for before_summon. */
val count: Int? = null,
/** The mobs just summoned, for on_summon. */
val mobs: List<Actor>? = null,
```

- **Algorithmic Steps & Invariants:**
  1. At the top of `World.summon`, after the `require`s: `dispatch(BEFORE_SUMMON, caster, caster.room, HookContext(actor
     = caster, summonLabel = label, count = count))`; a non-null denial → `send(caster, denial)` and return
     `emptyList()`.
  2. At the end, after the Phase 1A `on_owner_change` events: `dispatch(ON_SUMMON, caster, caster.room,
     HookContext(actor = caster, mobs = mobs))`, unless `mobs` is empty.
  3. `Ctx.kt`: `label` → `hc.summonLabel?.v ?: None`; `count` → `hc.count ?: None` as a Starlark int; `targets` returns
     `hc.mobs` as views when set, else its current behavior. Check that no existing ctx field is already named `label`
     or `count`; if one is, report it rather than shadowing it.
  4. Catalog: both attach to Room and Mob (observer); `before_summon` is vetoable.

### Test & Documentation Requirements

- A room `before_summon` that denies stops a `summon` spell's mobs (the cast still happened) and a `ctx.summon` call
  (returns `[]`), and the caster gets the message.
- A mob observer's `before_summon` sees `ctx.label` and `ctx.count`.
- `on_summon` sees `ctx.targets` in spawn order, after `on_owner_change`.
- CHANGELOG bullets.

### Exit Conditions

- [x] `./gradlew check` passes with zero warnings.
- [x] `SummonHooksTest` and the script summon tests pass.

---

## Phase 8: Storing pets with their owner across logout and restart

### Objective & Scope

- **In Scope:**
  - At quit (and so at link-dead and idle expiry), pets leave the world and are saved, not deleted.
  - At boot, mob rows with an owner, and items they hold, are held out of the world.
  - At login, the owner's pets are restored beside them, with their items, `owner:` reasons and owned lists.
  - Dropping a held pet whose owner's character or prototype is gone.
- **Out of Scope:** *(the agent must not attempt any of these here)* a stable or pet-slot limit, any change to
  `Repro.kt` unless its tests fail, `@`-commands for pets.

### Context Working Set

- **Read / Reference Only:**
  - `core/src/main/kotlin/gups/core/world/Restore.kt`: `restoreMob`.
  - `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt`: `restore` (the `owned` set and `world0`),
    `ownerOf(row)`, `loadCharacter`, `placeItems`, `checkpoint`.
  - `persist/src/main/kotlin/gups/persist/Codec.kt`: `MobPayload`, `applyMob`, `restoreReasons`.
  - `core/src/main/kotlin/gups/core/world/Sessions.kt`: `quit`, `unload`.
- **Files to Modify / Create:**
  - `core/src/main/kotlin/gups/core/world/World.kt` (`removeActor`'s `stow` flag)
  - `core/src/main/kotlin/gups/core/world/Owners.kt` (`stowPets`, `restorePet`)
  - `core/src/main/kotlin/gups/core/world/Sessions.kt` (`quit`, `unload`)
  - `core/src/main/kotlin/gups/core/world/KernelMessages.kt` (`PET_LEAVES`, `PET_ARRIVES`)
  - `persist/src/main/kotlin/gups/persist/SqlitePersistence.kt`
  - `persist/src/main/kotlin/gups/persist/Codec.kt` (only if `restoreReasons` needs a filter parameter)
  - Tests: `persist/src/test/kotlin/gups/persist/PersistenceTest.kt` (new cases), `OwnersTest.kt`.
  - `docs/SPEC.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** `Combat.kt`, `Behaviors.kt`, `script/`, `plugin-api/`.

### Specification & Decision Record Changes

- §5.12 logout sentence ("On logout the character and everything it carries are captured in an immediate batch, then
  removed from the world; their rows stay."): append "So are the player's pets, wherever they are: each leaves the world
  with what it carries and is saved with the character. At login they come back beside the player, in ascending id. A
  pet whose prototype is gone is dropped with a log note."
- §3.6 persistence: add "A mob with an owner is held out of the world when the world is restored, with the items it
  holds, until its owner logs in; one whose owner's character no longer exists is deleted with a note."
- §5.9 "Active vs anchor"/restart text: add "The `owner:` reason is not restored from a save; it is set again when the
  pet is restored beside its owner."

### Implementation Blueprint

- **Signatures & Types:**

```kotlin
// World.kt
/** Takes [a] and everything it carries out of the world; [stow] keeps a mob's rows for a later restore. */
internal fun removeActor(a: Actor, stow: Boolean = false)

// Owners.kt
/** Pets of [p] leave the world, saved and not deleted; returns the opponents this freed. */
internal fun World.stowPets(p: Player, pets: List<Mob>)
/** Places restored pet [m] beside [owner], resets its reason and owned list. */
fun World.restorePet(m: Mob, owner: Player)
```

- **Algorithmic Steps & Invariants:**
  1. `removeActor(a, stow)`: when `a is Mob && stow`, do not add `a.id` to `removed` and do not `markRemoved` its items,
     and do not touch its spawn rule (an owned mob has none). Everything else (indexes, `inWorld = false`,
     `removedThisPhase`, `forgetItemTree`) is unchanged.
  2. `quit(player)`: replace the Phase 1A interim loop. After `val freed = unlinkAll(player)`: `val pets =
     petsOf(player)`; for each pet: `freed += unlinkAll(pet)` (make `freed` mutable), `clearActive(pet,
     ownerReason(player.id))`, `pet.dirty = true`, `markDirtyTree` each carried and equipped item. Keep the existing
     checkpoint call where it is, so the pets' rows are written in the same immediate batch as the player. After the
     checkpoint and before `removeActor(player)`: for each pet, `tellSight` its room `PET_LEAVES` ("{pet} leaves with
     {owner}."), then `removeActor(pet, stow = true)`. `fireDisengage(freed, "stop")` stays last.
  3. `unload(player)`: also `removeActor(pet, stow = true)` for `petsOf(player)`, so an aborted login leaves no pets
     behind.
  4. Persistence `restore` (boot): decode each mob row's `MobPayload`; a row with `owner != null` is added to the
     held-out set, like player-owned items. Extend the private `ownerOf(row)` so an item whose holder chain ends at a
     held mob resolves to that mob's `owner`. Then `world0` excludes held mobs and their items. A held mob whose owner
     has no row in `playerRows` is added to `pendingDeletes` with a note "pet <label> #<id> dropped: its owner is gone",
     and so are its items.
  5. `loadCharacter`: after `applyPlayer`, before `placeItems`: for each mob row whose payload `owner == row.id`, in
     ascending id: no prototype → delete with a note; else `world.restoreMob(EntityId(id), proto, room, player's
     index)`, `applyMob`, then `world.restorePet(m, player)`. Then `placeItems` with the existing filter, which now also
     matches pet-held items because of step 4.
  6. `restorePet(m, owner)`: `applyMob` has already called `restoreOwner` (which dropped any saved `owner:` reason);
     check `m.owner == owner.id`, then `setActive(m, ownerReason(owner.id))`, `refreshOwnedBehaviors(m)`,
     `forgetActorSlots(m)`, `clampVitals(m)`. Fire no hooks. After the player has entered the world (where login
     completes; find where the login flow announces arrival), `tellSight` `PET_ARRIVES` ("{pet} arrives with {owner}.")
     for each restored pet. If no clean place exists for that message, skip it and note so in the CHANGELOG.
  7. Affects whose `expiresAt` passed while stored are handled by the normal expiry pass on the next pulse; a timed
     summon therefore fades right after login. Do not special-case it.
  8. Invariant to assert in tests: after a quit and a checkpoint, the pet's row and its items' rows still exist; after
     login the pet's id is the same.

### Test & Documentation Requirements

- `PersistenceTest.kt`:
  - Quit with a pet carrying an item → rows kept; the pet is not in the world; login → pet beside the player with the
    item, same ids, `owner:` reason set, owned list computed.
  - Shutdown checkpoint with the owner online, then restore → the pet is not in the world until the owner logs in.
  - Owner's character deleted → the held pet and its items are deleted with a note.
  - Pet prototype removed → dropped at login with a note; the player still logs in.
  - Two pets come back in ascending id.
- `OwnersTest.kt`: quit fires `on_disengage` for the pet's opponents; the pet leaves the room with the message.
- CHANGELOG bullet.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings.
- [] `./gradlew :persist:test` passes, including the new cases and the crash tests.

---

## Phase 9: Template-world reference, end-to-end scenario, and finalization

### Objective & Scope

- **In Scope:**
  - A summonable familiar in the template world, a `familiar` spell, and a `pet_test.star` covering the agreed behavior
    end to end.
  - The §7.4 M38 row.
  - Marking the `DESIGN_NEEDED` entry completed.
- **Out of Scope:** *(the agent must not attempt any of these here)* new engine code. If a test exposes an engine bug,
  fix it in the smallest way in the file a previous phase changed and say so in the CHANGELOG.

### Context Working Set

- **Read / Reference Only:**
  - `template-world/skills/arcane/spells.star` (spell shape), `template-world/zones/tutorial/mobs.star`
    (`training_dummy`), `template-world/zones/tutorial/group_test.star` (test style, `_victim`),
    `template-world/rules/combat.star` (`xp_share` already pays `c.owner`).
  - `AGENTS.md` "End-to-end checks" for `gups scenario`.
- **Files to Modify / Create:**
  - `template-world/zones/tutorial/mobs.star` (a `familiar` mob)
  - `template-world/skills/arcane/spells.star` (a `familiar` spell)
  - the trainer or starting setup only if a test needs the spell taught (prefer `w.set_skill` or the harness equivalent
    in the test)
  - `template-world/zones/tutorial/pet_test.star` (new)
  - `docs/SPEC.md` §7.4, `docs/DESIGN_NEEDED.md`, `docs/CHANGELOG.md`
- **Do Not Touch:** engine Kotlin, except as Out of Scope allows.

### Specification & Decision Record Changes

- §7.4: add this row after M37 (one line in the file; `markdownFormat` pads it):

```markdown
| **M38**   | Pet ownership: the `owner` field with `set_owner`/`clear_owner` and `on_owner_change`; `summon` ownership; behavior priorities, `heel`, `defend` and owned behavior lists with the `owned_behaviors` export and the `addOwnedBehaviors` plugin seam; the `order` command with `before_order`, the `ORDER` restriction and its run-time re-check; owner and pet engagement coupling; `before_summon` and `on_summon`; pets stored with their owner; and the template-world familiar. |
```

### Implementation Blueprint

- **Content:**
  1. `mob("familiar", name = "a spectral familiar", keywords = ["familiar", "spectral"], desc = ..., vitals = vitals(hp
     = 20), behaviors = [wander()])`: the `wander` shows that ownership replaces it.
  2. `skill("familiar", kind = "spell", cost = vitals(mana = 12), lag = 4, range = "self", target = "self", effect =
     summon("//zones/tutorial:familiar", duration = 600))`, with a comment. Check how a spell in `//skills/arcane` names
     a mob in another package (the label form used elsewhere for cross-package labels).
- **`pet_test.star` cases** (driven with the harness API; mirror `group_test.star`):
  1. Casting `familiar` gives a familiar whose `owner` is the caster; it follows the caster two rooms away and does not
     wander.
  2. `order familiar kill dummy`: the dummy and the caster are both engaged; the caster has not typed `kill`.
  3. The caster flees: the familiar is no longer fighting the dummy.
  4. Sequence exploit: `order familiar <exit>` then `order familiar kill <a mob in that room>` → the kill is dropped and
     the mob is not engaged with the familiar.
  5. A kill by the familiar pays the caster through `xp_share` (the pool goes to `c.owner`).
  6. A world `owned_behaviors` override is not needed for the template; leave the default.
- **Finalization:**
  - In `docs/DESIGN_NEEDED.md`, under `### Pets and followers bonded to a player`, add as the first line under the
    heading: `**Status:** Completed in Milestone 38; pending designer review.` Do not delete the entry.
  - Run the full verification below.

### Test & Documentation Requirements

- `pet_test.star` passes under `gups test`.
- One `gups scenario` run in `local-world-test/` (then deleted) reproducing cases 2 to 4 with typed commands, per
  `AGENTS.md`.
- CHANGELOG: a closing bullet naming the template familiar.

### Exit Conditions

- [] `./gradlew check` passes with zero warnings (includes `markdownCheck` and the template-world tests).
- [] `cli/build/install/gups/bin/gups test template-world` (or the project's documented template test command) passes.
- [] `grep -n "Completed in Milestone 38" docs/DESIGN_NEEDED.md` finds the entry.
- [] `grep -rn "bond" docs/SPEC.md core/src/main script/src/main` finds no pet-related use.
