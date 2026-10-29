# Milestone 8: Disabling Accounts

Instructors can disable and re-enable accounts in game with `@role`, and in bulk through the roster CSV. Disabling quits
the account's characters at once, closes its other sessions, deletes its web tokens and unused setup codes, and records
who disabled it, when and why.

Read [AGENTS.md](../AGENTS.md) before starting. The source of truth is [SPEC.md](SPEC.md):

|              Section              |                                             What it says about this milestone                                              |
|-----------------------------------|----------------------------------------------------------------------------------------------------------------------------|
| §3.5 Accounts and Roles           | The disabled record (`disabledBy`, `disabledAt`, `disabledReason`), what disabling does, and the store's `update` contract |
| §3.6 Persistence Schema           | The `disabled_*` columns (migration 4)                                                                                     |
| §4.9 Commands, `@role`            | `@role <account> disable [reason]` and `enable`, the permission rules, messages, and what `@role <account>` shows          |
| §4.13 CLI, `gups import-roster`   | The optional `disabled` column and its effects                                                                             |
| §4.14 Admin Port                  | `POST /roster` closes accounts it disables                                                                                 |
| §5.12 Sessions and Authentication | The roster column, and disabling deletes tokens                                                                            |

If this document and the spec disagree, the spec wins. Log the disagreement in [DECISIONS.md](DECISIONS.md) and point it
out in your summary.

## Scope

In scope:

1. Migration 4 and the `Account` fields for the disabled record.
2. A store operation that disables or enables an account in one batch.
3. Closing every session of a disabled account on the world thread, with no window for a racing login.
4. `@role <account> disable [reason]`, `@role <account> enable`, and the extra line in `@role <account>`.
5. The roster's `disabled` column, offline and through `POST /roster`.

Out of scope (don't build):

- Timed suspensions, or a `login` restriction capability. Restrictions don't change.
- A server-side periodic re-check of the disabled flag. An offline import beside a running server takes effect at the
  next login, and only warns.
- A new CLI subcommand or admin endpoint beyond the existing `/roster`.
- Plugin API changes. `PluginWorld` doesn't expose accounts, so `PluginApi.VERSION` stays as it is.
- A CHANGELOG entry or `api_version` bump. Nothing here is script-facing.

## Where things are today

- `core/src/main/kotlin/gups/core/Accounts.kt`: `Account` (already has `disabled`) and the `AccountStore` interface.
- `persist/src/main/kotlin/gups/persist/Database.kt`: `MIGRATIONS` (append-only; migration 3 is the latest), the account
  `INSERT`/`UPDATE` statements, and `AccountOp` execution.
- `persist/src/main/kotlin/gups/persist/Accounts.kt`: the SQLite `AccountStore`. Reads use its own read-only connection,
  and writes are immediate batches through the checkpoint writer (`submit(AccountOp...)`).
- `auth/src/main/kotlin/gups/auth/AccountTools.kt`: `@role` and its `reset`/`revoke` sub-commands, `describe`,
  `mayManage`, and `World.applyAccount`.
- `auth/src/main/kotlin/gups/auth/AccountLogin.kt`: the telnet and WebSocket login state machine. Its `conns` map holds
  each pre-game connection's `account`. It already refuses a disabled account after the password check, and in
  `connected` for token sessions.
- `auth/src/main/kotlin/gups/auth/WebLogins.kt`: `accountFor` already ignores tokens of disabled accounts.
- `auth/src/main/kotlin/gups/auth/Accounts.kt`: `RosterEntry`, `RosterResult`, `AccountService.importRoster`, and
  `Roster.parse` (whose `COLUMNS` is `login`, `github_handle` and `role`).
- `core/src/main/kotlin/gups/core/world/Sessions.kt`: `World.quit(player, message)` (ignores combat, checkpoints
  immediately, closes the connection) and `World.closeSession`.
- `core/src/main/kotlin/gups/core/world/Login.kt`: the `LoginHandler` interface.
- `core/src/main/kotlin/gups/core/world/Operations.kt`: `World.later(work, then)`, which runs store calls off the world
  thread and continues on it.
- `cli/src/main/kotlin/gups/cli/GupsServer.kt`: the admin port's `roster(csv)` (runs on a Ktor IO thread, not the world
  thread).
- `cli/src/main/kotlin/gups/cli/Commands.kt`: `ImportRoster` (the offline path and summary).
- Tests: `auth/src/test/.../RolesTest.kt` (`@role`), `AccountLoginTest.kt`, `WebLoginsTest.kt`, `AuthTest.kt` (roster),
  `LoginFixture.kt` (`MemoryAccountStore`), `persist/src/test/.../AccountStoreTest.kt`,
  `net/src/test/.../AdminServerTest.kt`, `cli/src/test/.../CommandsTest.kt` and `GupsServerTest.kt`.

## Work plan

Work in this order. Write each step's tests from the spec before or with the code. Don't work backward from what the
code happens to do.

### 1. Re-read the spec and refine it if needed

Read every section listed above, then the code listed under "Where things are today". If the spec leaves something
unsettled that you need, don't edit `SPEC.md`. Do two things instead:

- Log your choice in `DECISIONS.md` (next free `D-NNN`, status `open`).
- Write the exact proposed SPEC wording in your final summary, for the maintainers to approve.

Things that probably need a decision entry:

- The exact accepted spellings of `true`/`false` beyond those the spec lists. Accept only the listed ones.
- The roster parse error for a bad `disabled` value. Make it match the existing errors, for example `line 4: 'maybe' is
  not true or false`.
- The reply text for `disable`/`enable`, for a no-op, and for a refusal.
- The exact summary and warning wording of `gups import-roster`.

### 2. Schema and model

- Append migration 4 to `MIGRATIONS`: `ALTER TABLE accounts ADD COLUMN disabled_by INTEGER`, `... disabled_at INTEGER`
  and `... disabled_reason TEXT`. Never edit migrations 1–3.
- Add to `Account`: `disabledBy: Long?` (the disabling account's id; null for the roster or an account-less `--dev`
  character), `disabledAt: Long?` (wall-clock ms) and `disabledReason: String?`. Default all three to null.
- Read and write the columns in `persist`: the `SELECT` in `Accounts.kt`, and the `INSERT` and `UPDATE` in
  `Database.kt`.
- Check the sanitizer. It already empties `accounts`, so no change should be needed. Confirm with a test.

### 3. The store operation

Add to `AccountStore`:

```kotlin
/**
 * Disables or enables account [id] in one immediate batch. Disabling stores the record and
 * deletes the account's session tokens and unused setup codes; enabling clears the flag and
 * the record. Returns false, changing nothing, if the flag already had that value.
 */
fun setDisabled(id: Long, disabled: Boolean, by: Long?, at: Long, reason: String?): Boolean
```

- Implement it in `persist` as one new `AccountOp`, executed as one transaction:
  - update `accounts`
  - `DELETE FROM session_tokens WHERE account_id = ?`
  - `DELETE FROM invites WHERE account_id = ? AND used_by IS NULL`
- Make "already in that state" a reported result, like a taken login, never an exception. That keeps it from wedging the
  writer's retry loop.
- Implement it in `MemoryAccountStore` (test fixture) too.
- `update` must keep writing `disabled` and the record as given, so `setRole` doesn't clear them. Make sure `setRole`'s
  `a.copy(...)` carries them through.

### 4. Closing a disabled account's sessions

Add one world-thread entry point, for example in `auth`:

```kotlin
/** Quits [accountId]'s characters and closes its pre-game sessions. World thread only. */
fun World.closeDisabledAccount(accountId: Long)
```

- For every player with `accountId == accountId`, call `quit(p, "This account has been disabled.")`. This includes
  link-dead and engaged players: `World.quit` ignores combat.
- Add a `LoginHandler` method, defaulting to a no-op: `fun accountDisabled(world: World, accountId: Long)`. In
  `AccountLogin`, close every connection in `conns` whose `account` has that id. Send the same line, then call
  `closeSession`.
- **The race.** A login can read the account off the world thread before the disable commits, and continue on the world
  thread after the kick. To close that window:
  - `AccountLogin` keeps an in-memory set of account ids disabled during this run. Add to it in `accountDisabled`, and
    remove from it on enable (add an `accountEnabled` hook, or make the method take the new state).
  - Every world-thread continuation that hands a connection an account checks the set and refuses with "This account is
    disabled.": the password continuation, the token continuation in `connected`, and the redeem continuation.
  - The set needs no persistence. After a restart, every login reads the committed flag.
- The `--dev` login has no disable path. Leave it alone.

### 5. `@role disable` and `@role enable`

In `AccountTools.role`, add `"disable"` and `"enable"` next to `"reset"` and `"revoke"`. Everything after `disable` is
the reason; an empty reason is stored as null.

- **Refusals**, checked on the world thread before any store call:
  - The target is the instructor's own account.
  - The target is an admin and the instructor isn't one (reuse `mayManage`).
- **Flow:**
  1. `run(...)` calls `store.setDisabled(...)` off-thread with `by = p.accountId` and `at = w.wallClock()`.
  2. Back on the world thread, if the flag changed, disabling calls `closeDisabledAccount(a.id)` and the login handler's
     `accountDisabled`, then replies. Enabling notifies the login handler, then replies.
  3. Reply only after the kick, so "no session outlives the reply" holds.
- **No-op:** if `setDisabled` returns false, reply that the account was already disabled or enabled. Nothing else
  happens.
- **`describe`:** for a disabled account, add the line `disabled by <login> <wallTime(at)>: <reason>`. Look up
  `disabledBy`'s login in the off-thread half of the existing `run`, next to `byLogin`, never on the world thread. Write
  `disabled by the roster` when `disabledBy` is null and the reason is `roster`. Leave out `: <reason>` when there is
  none.
- Update the usage string: `Usage: @role <account> [<role> [packages] | reset | revoke | disable [reason] | enable]`.

### 6. The roster's `disabled` column

- Add `disabled` to `Roster.COLUMNS`. Parse `true`/`false`, `yes`/`no` and `1`/`0`, in any case. A blank cell or a
  missing column gives null. Any other value is a problem for that line, and fails the whole import as other bad rows
  do.
- Add `disabled: Boolean? = null` to `RosterEntry`. Add the accounts disabled and enabled to `RosterResult`, as login
  lists so the server can kick them.
- **`importRoster`:**
  - **New login, `disabled == true`:** create the account with `disabled = true`, `disabledReason = "roster"`,
    `disabledAt = clock()` and no `disabledBy`. Issue no setup code.
  - **Existing login whose flag differs from a non-null `disabled`:** call `setDisabled` (reason `roster`, by null).
    Count it as updated, even if nothing else changed.
  - **`disabled == null`:** never touch the flag.
  - Re-running the same roster changes nothing.
- **Offline `gups import-roster`:** add the disabled and enabled counts to the summary on standard error. If any account
  was disabled, also print a warning that characters already in the world weren't quit, pointing to `--admin`.
- **`POST /roster`** in `GupsServer`: after `importRoster` returns, post a world task (the same mechanism `later` uses)
  that calls `closeDisabledAccount` and the login handler's `accountDisabled` for each disabled account id, and
  `accountEnabled` for each enabled one. Answer the HTTP request only after that task has run. Keep the response format
  as it is.
- Update the `ImportRoster` help text to mention the `disabled` column.

### 7. Docs and cleanup

- Update [README.md](../README.md) or other user docs if they describe the roster columns or `@role`'s sub-commands.
- Make sure no code comment cites spec sections or decision IDs.
- Run `./gradlew check`.

## Tests

Name tests by behavior, as the existing ones do. Each item below is at least one test.

**persist** (`AccountStoreTest`): - Migration 4 runs on a database at schema version 3. Existing accounts read back with
a null record. - `setDisabled(true, …)` stores the flag and record, deletes the account's tokens and its unused bound
setup codes, and leaves other accounts' tokens and used invites alone. - `setDisabled` to the current state returns
false and changes nothing. - Enabling clears the flag and record. Deleted tokens stay deleted. - `update` round-trips
the disabled record. - A sanitized copy has no accounts.

**auth** (`RolesTest`, `AccountLoginTest`, `WebLoginsTest`, `AuthTest`): - `@role ann disable dropped the course` quits
Ann's character with "This account has been disabled.", including when it is link-dead and when it is engaged in combat.
The character is checkpointed and no longer in the world. - A connection of the account sitting at the character menu
gets the line and is closed. So does a WebSocket session that authenticated by token. - The instructor's reply comes
after the kick, and `@role ann` then shows `disabled by <login> <time>: dropped the course`. - Disabling with no reason
stores null and shows no `: ` suffix. - Refused on your own account. Refused on an admin's account by an instructor, and
allowed by an admin. - Disabling twice: the second reply says it was already disabled, and the stored record (who, when,
why) is still the first one's. - `@role ann enable` clears the flag and the `@role ann` line. Ann can log in again with
their password. Their old web token still doesn't work. - **The race:** with a store fake whose `byLogin` blocks until
released, start a login, disable the account and let the kick run, then release the login. The login is refused with
"This account is disabled." and never reaches the menu. - A web token issued before the disable is refused afterward
(`accountFor` returns null) and is gone from the store. - The setup code of a password-less roster account is unusable
after a disable. - **Roster parsing:** the `disabled` column in any position, every accepted spelling, blank, missing
column, and a bad value reported with its line number and nothing imported. - **Roster import:** - A new login with
`disabled=true` is created disabled with reason `roster`, and gets no setup code. - `false` re-enables. - A blank cell
leaves a disabled account disabled. - A flag-only change counts as updated. - A second identical import reports
everything unchanged.

**net / cli** (`AdminServerTest`, `GupsServerTest`, `CommandsTest`): - `POST /roster` with `disabled=true` for a
logged-in player quits that character before the response returns. - Offline `gups import-roster` with a disabled row
prints the counts and the warning, and writes the flag.

## Success criteria

- [] Every test above exists and passes.
- [] `./gradlew check` passes, including the module-graph check, with no Kotlin warnings. `auth` still depends only on
  `core`.
- [] Migration 4 is appended, and migrations 1–3 are byte-for-byte unchanged.
- [] No session of a disabled account (in the world, link-dead, at the menu, or mid-login) survives `@role disable` or
  an admin-port roster import.
- [] Every interpretation the spec didn't settle is logged in `DECISIONS.md`, and any proposed SPEC wording is in your
  summary. `SPEC.md` is unedited.
- [] No code comment cites spec sections or decision IDs.
