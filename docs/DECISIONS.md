# Implementation Decisions Not Covered by the Spec

This log records choices made while implementing [SPEC.md](SPEC.md) where the spec is silent, ambiguous, or ahead of the
current milestone.

---

### Master Status Logs (Consolidated)

Next ID: D-201 - Ratified decisions are folded into [`SPEC.md`](SPEC.md) and removed from these logs. -
[`REVISE_DECISIONS.md`](REVISE_DECISIONS.md): Consolidated master record of decisions requiring code/spec revisions. -
Be sure to update the Next ID above when adding new decisions.

### D-200 A level step with no gains drops any older record for that level — `open`

The design stores a level's `stats` and `max` only when one is not empty, "replacing any older entry". It does not say
what happens to an older entry when the new step gives no gains. `levelUp` removes it, so that a later level loss
unwinds exactly what the most recent step to that level applied and never gains it did not give. Zero deltas and names
the actor does not have are left out of both what is applied and what is recorded. *Code:* `World.levelUp`.
