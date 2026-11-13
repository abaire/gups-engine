# Notes for Agents

## Working from the spec

The engine is built milestone by milestone from [docs/SPEC.md](docs/SPEC.md); each milestone is planned in its own
`docs/MILESTONE_NN.md`. The spec is the source of truth, but it doesn't settle every detail.

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

## Naming: skills, not spells

Everything a character casts is a `skill()`, and its `kind` is free-form; "spell" is only one kind. Name engine and
plugin identifiers (rules exports, `ctx` fields, hooks and events, plugin API types, message ids) with "skill":
`recognizes_skill`, not `recognizes_spell`. Documentation of `cast` may talk about spells, and player-facing text may
say "spell" where a world or a skill chooses that word.

## Writing code

- Follow test driven development practices. Write tests first, verify that they fail, then write code to pass the tests.

- **Plugin facade parity:** when you add or change a `ctx` verb or a view field, add or change its `PluginWorld` or view
  counterpart in the same change, or add it with a reason to the exception list in `CtxParityTest`. `./gradlew check`
  fails otherwise.

## CodeScene

After changing code, run `code_health_review` on the changed files. If Code Health drops, fix the issues, run the review
again, and use `code_health_score` to confirm the result. Before finishing, run `pre_commit_code_health_safeguard` and
only stop when it passes.

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

## End-to-end checks

When a milestone asks for an end-to-end check (players at a real zone, typing commands), use `gups scenario` instead of
scripting telnet against `gups serve --dev`. It boots the world in-process from its `.star` files, drives the same
command pipeline the server does, and skips only the network, login, real-time pacing and saved database state.

Write the scenario in `local-world-test/` (see below), not in the world and not outside the repository, using the
harness API of `*_test.star` files:

```python
def test_flee_then_trade(t):
    w = t.world(seed = 1)
    tess = w.spawn_player("Tess", at = ":shop")
    ash = w.spawn_player("Ash", at = ":shop")
    ash.do("kill shopkeeper")
    t.contains(tess.do("list"), "busy fighting")
    ash.do("flee")
    print(ash.room.label)
```

Then run it against the template world:

```bash
./gradlew :cli:installDist
cli/build/install/gups/bin/gups scenario --world template-world --package //zones/tutorial local-world-test/scenario.star
```

- The transcript tags each command with the player and the room it was typed in, so check where a player is before
  reading a reply as a bug. Output that reaches a player between commands appears when you call `p.output()` or that
  player's next `p.do(...)`.
- `p.do(line)` runs pulses until that player's command finishes, so players take turns; they don't act in the same
  pulse. Use `w.run(pulses = n)` to let time pass.
- Assertions (`t.contains`, `t.eq`, ...) make the run exit 1 on failure, with the transcript still printed.
- Scenario players are ordinary players, so admin commands such as `@trace` answer "Huh?". To see the rules chains, call
  `w.trace()` before the commands you care about: a scenario prints the trace record (hook calls and every `rules.*`
  line that `@trace rules` would show) after its transcript, whether it passes or fails.
- If the scenario should become a permanent test, move it into the package as a `*_test.star` file; it runs there
  unchanged.
- Only fall back to `gups serve --dev` for what the harness can't reach: login, the network layer, saved state.

## Ignored directories

`local-world/` is a gitignored developer artifact: a personal scratch copy of a world for running a dev server. It is
not part of the engine, is never tested or kept in sync, and may be stale. Never read, search, edit, lint, copy, run
against or investigate it, and ignore it in search results and when reporting what else might need updating.

`local-world-test/` is the agents' gitignored scratch area inside the repository. Use it, never `/tmp` or another path
outside the repository, for anything temporary: scenario files, and a world you need to change for an investigation.

- For a world of your own, copy the template: `cp -R template-world local-world-test/world`, then pass `--world
  local-world-test/world`. Never start from `local-world/`.
- Delete `local-world-test/` when you are done, and don't commit anything from it. If a scenario should stay, move it
  into the package as a `*_test.star` file.
