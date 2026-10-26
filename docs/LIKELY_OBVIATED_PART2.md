# Obviated Implementation Decisions — Part 2 (M2–M4)

This document tracks implementation decisions from [DECISIONS_PART2.md](DECISIONS_PART2.md) that were rendered irrelevant or superseded by changes in later milestones.

---

## Network, dev server and tooling (§2.4, §4.10, §5.10, §5.13) — M3

### D-092 `gups serve` options — `obviated`
`--prod` exits with status 3 until M6. `--fresh` and `--db` work from M4 (D-099, D-101).
Two options are new: `--port` (default 4000) and `--start` (D-082).
*Code:* `Serve`.

*Reason obviated:* In M3, `--prod` was stubbed to exit with status 3 until M6, and `--fresh`/`--db` were deferred to M4. M4 implemented `--fresh` and `--db` (D-099, D-101), and M6 implemented full `--prod` support (D-152, D-160, D-165) via `Serve.prodServer`. `--port` is in SPEC §4.13, and `--start` / start room semantics were superseded by `start_room` in `MODULE.gups` (D-138).
