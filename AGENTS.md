# Notes for Agents

## Working from the spec

The engine is built milestone by milestone from [docs/SPEC.md](docs/SPEC.md) (milestones
are in §7.4). The spec is the source of truth, but it doesn't settle every detail.

**Whenever you make a decision the spec doesn't specify, log it in
[docs/DECISIONS.md](docs/DECISIONS.md) in the same change as the code.** That covers
choices where the spec is silent or ambiguous, interpretations of unclear wording, default
values and formulas you picked, behavior deferred to a later milestone, and Kotlin seams
that differ from what the spec shows.

- Follow the format at the top of that file: the next free `D-NNN` ID, the spec section,
  what you decided, where the code lives, and status `open`. Don't renumber existing
  entries.
- If you change behavior that an existing entry describes, update that entry rather than
  adding a contradicting one.
- Don't edit `docs/SPEC.md` to match your decision. The maintainers review the log and fold
  accepted decisions into the spec themselves; rejected ones mean the code changes.
- If you can't tell whether something counts, log it. A spurious entry costs a minute of
  review; a silent decision can go unnoticed for good.

When you mention a decision in a code comment, cite its ID (for example `// D-005`) if it
helps a reader find the reasoning.

## Building and testing

See [README.md](README.md). `./gradlew check` must pass, including the module-graph check.
All Kotlin warnings are errors.
