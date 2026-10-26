# Implementation Decisions — Part 3: Simulation, Operations & Plugins (M5–M7)

This log records implementation choices for Milestones 5–7 (Rules/Simulation/Content, Accounts/Auth, Roles/Tools, TLS/GMCP, Web, Operations, CI Gates).
Milestone 7 (Documentation, API Stability & Plugins) has been split into [DECISIONS_PART2.md](DECISIONS_PART2.md).
Part of the decision review process split from [DECISIONS.md](DECISIONS.md).

**Format:** every entry has a stable ID, the spec section it relates to, what was decided,
and where the code lives. Statuses are `open` (not yet reviewed), `ratified` (folded into
the spec), or `rejected` (needs revision). Don't renumber entries.

---

## Operations and CI gates (§7)

### D-180 Code formatting with ktfmt in Google style (SPEC §7.4, §2.2) — `open`
Kotlin sources and build scripts are checked for consistent formatting using `com.ncorti.ktfmt.gradle` with Google style (2-space indent, 100-character column limit).
The convention plugin `gups.ktfmt` configures ktfmt and root-script validation. `./gradlew ktfmtCheck` and `./gradlew ktfmtFormat` cover all subprojects, `build-logic`, and root/standalone `.kts` scripts. `./gradlew check` depends on `ktfmtCheck`, and CI verifies formatting on every push and pull request.
*Code:* `build-logic/src/main/kotlin/gups.ktfmt.gradle.kts`, `build-logic/build.gradle.kts`, `build.gradle.kts`, `.github/workflows/ci.yml`.


