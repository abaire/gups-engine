# Prompt: apply a revised decision

Copy everything below the line into an agent session, replacing `<FILE>` with the decision
file's name (for example `D-004.md`).

---

Apply the revised decision in `docs/revised_decisions/<FILE>`.

These files hold maintainer feedback on entries in the decision log: each one names a
decision (`D-NNN`), says why the current behavior was rejected or needs revising, and
points at the code involved (`*Code:*`). Your job is to make the code match the feedback,
prove it with tests, bring the spec and decision log in line, and then delete the file.

Before you start, read `AGENTS.md`. Its rules still apply, with one exception: for this
task you may edit `docs/SPEC.md` without asking first, as long as the edit only covers
the behavior this decision changes.

## 1. Understand before changing anything

- Read the decision file. Then read every code location it names, and enough of the code
  around them to understand the current behavior and its callers.
- Read the spec sections it cites (`§x.y`) in `docs/SPEC.md`.
- Search for the decision ID in `docs/DECISIONS*.md`, in code comments and in tests
  (`grep -rn "D-NNN"`), so you can find the existing log entry and any tests that pin the
  current behavior.
- **Ask clarifying questions before you edit anything** if one of these is true:
  - the feedback could reasonably be read more than one way;
  - it contradicts the spec, another decision, or an existing test in a way the file
    doesn't address;
  - doing it would need a design choice the file doesn't settle, such as a new config
    option, a change to the public or plugin API, or a persistence or schema change;
  - the code it names no longer exists or has moved so far that the request no longer
    fits.

  Ask one focused question at a time, and say which reading you'd pick and why. If none
  of these apply, go ahead without asking.

## 2. Implement

- Keep the change as small as possible and match the style of the code around it:
  naming, comment density, KDoc on new functions.
- Cite the decision in a short comment where the reason isn't obvious, for example
  `// ... (D-NNN)`.
- If a function only needs to be visible so a test can check it, make it `internal`,
  keep it small and document it.

## 3. Test

- Add at least one test that fails under the old behavior and passes under the new one.
  Put it in the existing test class for that area (look in
  `core/src/test/kotlin/gups/core/world/` and the matching folders in other modules), and
  reuse the `Fixture`, `FixedRules`, `RecordingHost` and corpus helpers (`T`, `C`, …).
- Name the test after the behavior, not the decision, and mention the ID in an
  assertion message or comment.
- Make sure the test actually separates old behavior from new: either temporarily revert
  your change and watch it fail, or explain in your report exactly why it would fail.
- Update any existing test that pinned the rejected behavior. Don't delete it unless the
  behavior it covered no longer exists.

## 4. Update the docs

- **Spec:** if `docs/SPEC.md` describes the old behavior, or doesn't say enough for a
  reader to predict the new behavior, change the relevant sentences. Keep the spec's tone
  and cross-reference style (`§n.n`), and don't rewrite text the decision doesn't touch.
  If the spec already matches the new behavior, leave it alone and say so.
- **Decision log:** if there's an entry for this ID in `docs/DECISIONS*.md`, update it to
  describe the new behavior and where the code lives, as `AGENTS.md` asks. Don't add a
  second entry that contradicts it. If you had to make a new decision the spec doesn't
  cover, log it under the next free ID.
- If the change alters any code that `docs/PLUGINS.md` or another document quotes, update
  the quoted copy. A `cli` test compares them.

## 5. Verify and finish

- Run `./gradlew ktfmtFormat`, then `./gradlew check`. Both must pass, and Kotlin warnings
  count as errors. If the build is broken for a reason outside your change, find the
  cause instead of assuming it isn't your problem. Ask before fixing anything that isn't
  clearly related.
- Delete `docs/revised_decisions/<FILE>` only once `check` passes.
- Don't commit unless you're asked to.
- Report:
  - what changed in the code;
  - the new test(s), and why each one would fail under the old behavior;
  - the spec and decision-log edits, quoting the new spec wording;
  - any behavior you noticed but didn't change, such as edge cases;
  - anything you couldn't verify.
