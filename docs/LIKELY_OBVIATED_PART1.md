# Obviated Implementation Decisions — Part 1 (M1)

This document tracks implementation decisions from [DECISIONS_PART1.md](DECISIONS_PART1.md) that were rendered irrelevant or superseded by changes in later milestones.

---

## Kotlin seams and types (§3, §4.11)

### D-059 Unsupported control events — `obviated`
`Deploy` is accepted but only logs a `control` diagnostic until M6. `Reload` works from M3
(D-081).
*Code:* `World.handle(WorldControlEvent)`.

*Reason obviated:* In M1, `Deploy` and `Reload` were interim stubs logging diagnostics. `Reload` was implemented in M3 (D-081) and `Deploy` was implemented in M6.5 via the deployment pipeline (D-162). Both are fully supported in `World.handle(WorldControlEvent)` and neither logs a control diagnostic as unsupported.
