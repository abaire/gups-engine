# Implementation Decisions — Part 1: Core Engine & Mechanics (M1)

This log records implementation choices for Milestone 1 (Commands, Action Lifecycle, Tactical Track, Combat, Rules, Hooks, Seams).
Part of the decision review process split from [DECISIONS.md](DECISIONS.md).

**Format:** every entry has a stable ID, the spec section it relates to, what was decided,
and where the code lives. Statuses are `open` (not yet reviewed), `ratified` (folded into
the spec), or `rejected` (needs revision). Don't renumber entries.

---

## Items and affects (§4.2, §4.6)

### D-181 Worn items apply their affects as real affects (SPEC §4.2, §4.6, §5.4.1) — `open`
Equipping an item applies each `equip_affects` entry to the wearer through `applyAffect`, named
`worn:<item id>:<flag>`. An untimed entry carries `wornFrom` (the item's id) and never expires
(`expiresAt = Long.MAX_VALUE`); removing or destroying the item removes it. A timed entry
(`{"flag": ..., "pulses": n}`) is an ordinary timed affect with no `wornFrom` and runs out even
if the item stays on; re-equipping restarts it. Untimed worn affects aren't saved in checkpoints:
`restoreItem` rebuilds them from what is worn, and `restoreAffects` keeps them. `Char.Affects`
reports their `remaining` as `null` and `@stat` shows them as "while worn". Equipment
`stat_deltas` and `modifiers` still apply directly and are not affects.
*Code:* `EquipAffect` in `Protos.kt`, `Affect.wornFrom` in `Entities.kt`, `applyEquipAffects` and
`removeEquipAffects` in `Combat.kt`, `index`/`unindex` in `Items.kt`, `restoreItem` in
`Restore.kt`, `affectsJson`/`restoreAffects` in `persist/Codec.kt`, `equipAffects` in
`script/Declarations.kt`.
