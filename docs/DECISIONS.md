# Implementation Decisions Not Covered by the Spec

This log records choices made while implementing [SPEC.md](SPEC.md) where the spec is
silent, ambiguous, or ahead of the current milestone.

To enable parallelized review across multiple agent sessions without file stomping or git conflicts,
the decisions log has been partitioned into 3 logical subdocuments:

1. **[DECISIONS_PART1.md](DECISIONS_PART1.md) — Part 1: Core Engine & Mechanics (Milestone 1)**
   - Covers: Commands, Action Lifecycle, Tactical Track, Combat, Default Ruleset, Event Dispatch & Hooks, Kotlin Seams & Types.
   - Decision IDs: `D-001` through `D-059`
   - Active review files: [`REVISE_DECISIONS_PART1.md`](REVISE_DECISIONS_PART1.md) and [`LIKELY_OBVIATED_PART1.md`](LIKELY_OBVIATED_PART1.md)

2. **[DECISIONS_PART2.md](DECISIONS_PART2.md) — Part 2: Scripting, Networking, Persistence & Plugins (Milestones 2–4, Milestone 7)**
   - Covers: Script Host (Starlark/limits), Network & Dev Server Tooling, Checkpoints & Persistence, and Script API Model & Plugins (split from Part 3).
   - Decision IDs: `D-060` through `D-107`, and `D-171` through `D-179`
   - Active review files: [`REVISE_DECISIONS_PART2.md`](REVISE_DECISIONS_PART2.md) and [`LIKELY_OBVIATED_PART2.md`](LIKELY_OBVIATED_PART2.md)

3. **[DECISIONS_PART3.md](DECISIONS_PART3.md) — Part 3: Simulation & Operations (Milestones 5–6)**
   - Covers: Rules & Simulation (Bubble/Perception/Behaviors), Accounts & Login, Roles & In-Game Tools, TLS & GMCP, Web Client & Sockets, Operations & CI Gates.
   - Decision IDs: `D-108` through `D-170`, and `D-180`
   - Active review files: [`REVISE_DECISIONS_PART3.md`](REVISE_DECISIONS_PART3.md) and [`LIKELY_OBVIATED_PART3.md`](LIKELY_OBVIATED_PART3.md)

---

### Master Status Logs (Consolidated)
- Ratified decisions are folded into [`SPEC.md`](SPEC.md) and removed from these logs.
- [`REVISE_DECISIONS.md`](REVISE_DECISIONS.md): Consolidated master record of decisions requiring code/spec revisions.
