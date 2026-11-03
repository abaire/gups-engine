# Notes for Agents

## Working from the spec

The engine is built milestone by milestone from [docs/SPEC.md](docs/SPEC.md) (milestones are in §7.4). The spec is the
source of truth, but it doesn't settle every detail.

**`docs/DECISIONS.md` is only added to when an agent makes decisions to fill gaps in the spec without asking for
approval first.** It is a mechanism to allow humans to review those decisions and adjust or ratify them before
incorporating them into `SPEC.md`. If the maintainer or human has approved updating `SPEC.md` directly, update `SPEC.md`
directly as part of the change and do not add an entry to `DECISIONS.md`.

When logging unapproved decisions in `docs/DECISIONS.md`: - That covers choices where the spec is silent or ambiguous,
interpretations of unclear wording, default values and formulas you picked, behavior deferred to a later milestone, and
Kotlin seams that differ from what the spec shows. - Follow the format at the top of that file: the next free `D-NNN`
ID, the spec section, what you decided, where the code lives, and status `open`. Don't renumber existing entries. - If
you change behavior that an existing entry describes, update that entry rather than adding a contradicting one. - Don't
edit `docs/SPEC.md` without explicit approval of the proposed change. Propose the exact wording first; never edit it
just to match a decision you made. The maintainers review the log and fold accepted decisions into the spec; rejected
ones mean the code changes. - If you can't tell whether something counts, log it. A spurious entry costs a minute of
review; a silent decision can go unnoticed for good.

Do not link back to specific sections of the spec or cite decision IDs (such as `// D-005` or `SPEC §2.4`) in code
comments, as they may become outdated or invalid as the specification evolves. Comments should explain why something is
done directly without linking back to spec sections or decision documents. Assume the reader is familiar with Kotlin and
avoid obvious comments.

## Writing code

- Follow test driven development practices. Write tests first, verify that they fail, then write code to pass the tests.

## Building and testing

See [README.md](README.md). `./gradlew check` must pass, including the module-graph check. All Kotlin warnings are
errors.

- **Markdown formatting:** Whenever Markdown documentation or tutorial files are modified, run `./gradlew
  markdownFormat` before committing. Markdown formatting is enforced by `./gradlew markdownCheck` (which runs as part of
  `./gradlew check`) and verified in GitHub CI.

- **Code blocks in Markdown lists:** the formatter caps indentation inside a fenced code block that sits in a list at 4
  spaces, so any line indented more deeply is silently flattened (nested Kotlin loses its structure). The cap counts
  from the start of the line, so code under a top-level bullet can nest one level and code under a nested list item
  can't nest at all. Keep code in a list within that limit, or move longer snippets out of the list into their own
  section with the fence at column 0 and refer to that section from the list item. After running `markdownFormat`, check
  that code blocks kept their indentation.

- **Java environment:** The build requires JDK 21.

- **Sandbox execution:** Gradle commands (including `./gradlew check`, `./gradlew markdownCheck`, and `./gradlew
  markdownFormat`) must be run outside standard sandbox isolation (`BypassSandbox: true`) so the Gradle client process
  can connect to the Gradle daemon over the local loopback socket (`127.0.0.1`).

## Ignored directories

`local-world/` is a gitignored developer artifact: a personal scratch copy of a world for running a dev server. It is
not part of the engine, is never tested or kept in sync, and may be stale. Never read, search, edit, lint or investigate
it, and ignore it in search results and when reporting what else might need updating.
