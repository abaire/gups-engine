package gups.persist

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import kotlin.io.path.absolutePathString

/** One schema step. Versions are consecutive from 1. */
class Migration(val version: Int, val statements: List<String>)

/** The engine's schema. Append new migrations; never edit applied ones. */
val MIGRATIONS: List<Migration> =
  listOf(
    Migration(
      1,
      listOf(
        "CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
        """CREATE TABLE accounts (id INTEGER PRIMARY KEY, login TEXT NOT NULL UNIQUE COLLATE NOCASE, pw_hash TEXT,
                roles_json TEXT NOT NULL, github_handle TEXT, disabled INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)""",
        "CREATE TABLE session_tokens (token_hash TEXT PRIMARY KEY, account_id INTEGER NOT NULL, expires_at INTEGER NOT NULL)",
        "CREATE TABLE invites (code_hash TEXT PRIMARY KEY, role TEXT NOT NULL, expires_at INTEGER NOT NULL, used_by INTEGER)",
        """CREATE TABLE players (id INTEGER PRIMARY KEY, account_id INTEGER, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
                payload_json TEXT NOT NULL, updated_pulse INTEGER NOT NULL)""",
        """CREATE TABLE world_entities (id INTEGER PRIMARY KEY, kind TEXT NOT NULL, proto_label TEXT NOT NULL,
                proto_version INTEGER NOT NULL, location_json TEXT, payload_json TEXT NOT NULL, updated_pulse INTEGER NOT NULL)""",
        "CREATE TABLE zone_states (package TEXT PRIMARY KEY, payload_json TEXT NOT NULL)",
        """CREATE TABLE standings (holder TEXT NOT NULL, subject TEXT NOT NULL, amount INTEGER NOT NULL,
                updated_pulse INTEGER NOT NULL, history_json TEXT NOT NULL, PRIMARY KEY (holder, subject))""",
        """CREATE TABLE item_events (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL, pulse INTEGER NOT NULL,
                wall_time INTEGER NOT NULL, from_json TEXT, to_json TEXT, cause TEXT NOT NULL)""",
        "CREATE INDEX item_events_item ON item_events (item_id)",
        """CREATE TABLE diagnostics (id INTEGER PRIMARY KEY AUTOINCREMENT, pulse INTEGER NOT NULL, wall_time INTEGER NOT NULL,
                package TEXT, label TEXT, hook TEXT, file TEXT, line INTEGER, kind TEXT NOT NULL, message TEXT NOT NULL,
                stack TEXT, ctx_json TEXT, seed INTEGER, repro_json TEXT)""",
      ),
    ),
    // Setup codes are invites bound to an account.
    Migration(2, listOf("ALTER TABLE invites ADD COLUMN account_id INTEGER")),
    // Per-account restrictions of capabilities.
    Migration(
      3,
      listOf(
        """CREATE TABLE restrictions (id INTEGER PRIMARY KEY AUTOINCREMENT, account_id INTEGER NOT NULL, capability TEXT NOT NULL,
                until INTEGER, reason TEXT, set_by INTEGER, created_at INTEGER NOT NULL, lifted_by INTEGER, lifted_at INTEGER)""",
        "CREATE INDEX restrictions_account ON restrictions (account_id)",
      ),
    ),
    Migration(
      4,
      listOf(
        "ALTER TABLE accounts ADD COLUMN disabled_by INTEGER",
        "ALTER TABLE accounts ADD COLUMN disabled_at INTEGER",
        "ALTER TABLE accounts ADD COLUMN disabled_reason TEXT",
      ),
    ),
    // Per-account counter of characters ever created.
    Migration(5, listOf("ALTER TABLE accounts ADD COLUMN creations INTEGER NOT NULL DEFAULT 0")),
  )

/** A diagnostics row. */
data class DiagnosticRow(
  val id: Long = 0,
  val pulse: Long,
  val wallTime: Long,
  val pkg: String?,
  val label: String?,
  val hook: String?,
  val file: String?,
  val line: Int?,
  val kind: String,
  val message: String,
  val stack: String?,
  val ctxJson: String?,
  val seed: Long?,
  val reproJson: String?,
)

/** An `item_events` row. */
data class ItemEventRow(
  val itemId: Long,
  val pulse: Long,
  val wallTime: Long,
  val fromJson: String?,
  val toJson: String?,
  val cause: String,
)

/** A `standings` row. */
data class StandingRow(
  val holder: String,
  val subject: String,
  val amount: Int,
  val updatedPulse: Long,
  val historyJson: String,
)

/**
 * SQLite database wrapper configured for Write-Ahead Logging (WAL) and atomic checkpoint
 * persistence.
 *
 * One [Database] instance encapsulates a single JDBC connection. Once the engine is running, the
 * background checkpoint writer thread is the sole writer through this connection; reader threads
 * and CLI tools access the database concurrently using separate read-only connections.
 *
 * ## SQLite PRAGMAs & Concurrency
 * - `PRAGMA journal_mode = WAL`: Write-Ahead Logging mode enables non-blocking concurrent reads
 *   while transactions are written.
 * - `PRAGMA synchronous = NORMAL`: Syncs WAL on checkpoints rather than every commit, ensuring
 *   durability against process crashes while reducing disk I/O overhead. The last commits may be
 *   lost on power loss.
 * - `PRAGMA busy_timeout = 5000`: Blocks for up to 5,000 ms on lock contention before throwing
 *   SQLite busy errors.
 *
 * ## Relational Schema (10 Tables)
 * 1. `meta`: Global key-value properties (`key TEXT PRIMARY KEY, value TEXT NOT NULL`). Stores
 *    engine metadata including `schema_version`, current monotonic game `pulse`, `next_entity_id`,
 *    `next_handle` (for timer and affect IDs), `world_commit`, `world_schema`, `world_features`,
 *    and `snapshot_taken_at` (sanitized copies).
 * 2. `accounts`: User accounts (`id, login, pw_hash, roles_json, github_handle, disabled,
 *    disabled_by, disabled_at, disabled_reason, created_at, creations`). Maintains Argon2id
 *    password hashes, assigned role and scopes (CODEOWNERS and manual builder scopes), disable
 *    tracking, and creation counter.
 * 3. `session_tokens`: Web client session tokens (`token_hash TEXT PRIMARY KEY, account_id,
 *    expires_at`). Stores SHA-256 digests of active session tokens for the web client.
 * 4. `invites`: Registration invites and account setup codes (`code_hash TEXT PRIMARY KEY, role,
 *    expires_at, used_by, account_id`). Stores SHA-256 hashes of 10-character codes.
 * 5. `restrictions`: Disciplinary capability restrictions (`id, account_id, capability, until,
 *    reason, set_by, created_at, lifted_by, lifted_at`). Applies restriction ladders (such as chat,
 *    tell, or build suspensions) per account.
 * 6. `players`: Persisted player character state (`id, account_id, name, payload_json,
 *    updated_pulse`). Contains JSON payload with stats, vitals, affects, custom attributes,
 *    choices, granted traits, and level gains. Does NOT store inventory items; items reference
 *    players.
 * 7. `world_entities`: Live in-game entities (`id, kind, proto_label, proto_version, location_json,
 *    payload_json, updated_pulse`). Stores rooms, mobs, and all items (whether in a room, carried
 *    by an actor, or inside a container).
 * 8. `zone_states`: Per-package state (`package TEXT PRIMARY KEY, payload_json`). Maintains zone
 *    reset deadlines, mob respawn deadlines, and package quarantine status. A quarantined non-zone
 *    package gets a row holding only its quarantine flag.
 * 9. `item_events`: Item custody audit log (`id, item_id, pulse, wall_time, from_json, to_json,
 *    cause`). Immutable log capturing transfers involving players (`spawn`, `move`, `destroy`).
 * 10. `diagnostics`: Script and engine failure diagnostics (`id, pulse, wall_time, package, label,
 *     hook, file, line, kind, message, stack, ctx_json, seed, repro_json`). Records failure traces,
 *     execution context, and repro bundles for offline replay.
 *
 * ## Migrations
 * - **Migration 1:** Initial database schema creating the original 10 tables and indexes.
 * - **Migration 2:** Adds `account_id` to `invites` to support account-bound setup codes.
 * - **Migration 3:** Creates `restrictions` table and index for per-account capability suspensions.
 * - **Migration 4:** Adds `disabled_by`, `disabled_at`, and `disabled_reason` columns to `accounts`
 *   for disable auditing.
 * - **Migration 5:** Adds `creations` column to `accounts` to count characters created.
 */
class Database private constructor(val file: Path, private val conn: Connection) : AutoCloseable {
  val schemaVersion: Int
    get() = readSchemaVersion(conn)

  fun meta(): Map<String, String> =
    query("SELECT key, value FROM meta") { it.getString(1) to it.getString(2) }.toMap()

  /** Sets one `meta` key at once, outside a checkpoint. */
  fun setMeta(key: String, value: String) {
    prepare("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)") { st ->
      st.setString(1, key)
      st.setString(2, value)
      st.executeUpdate()
    }
  }

  /** Removes one `meta` key at once, outside a checkpoint. */
  fun deleteMeta(key: String) {
    prepare("DELETE FROM meta WHERE key = ?") { st ->
      st.setString(1, key)
      st.executeUpdate()
    }
  }

  /**
   * A server wipe: deletes every character, every item row and the item audit trail. Accounts,
   * restrictions, session tokens, invites and the rest of the world state stay.
   */
  fun wipeCharacters() {
    transaction {
      conn.createStatement().use { st ->
        st.executeUpdate("DELETE FROM players")
        st.executeUpdate("DELETE FROM world_entities WHERE kind = 'item'")
        st.executeUpdate("DELETE FROM item_events")
      }
    }
  }

  fun entityRows(): List<EntityRow> =
    query(
      "SELECT id, kind, proto_label, proto_version, location_json, payload_json, updated_pulse FROM world_entities ORDER BY id"
    ) {
      EntityRow(
        it.getLong(1),
        it.getString(2),
        it.getString(3),
        it.getInt(4),
        it.getString(5),
        it.getString(6),
        it.getLong(7),
      )
    }

  fun playerRows(): List<PlayerRow> =
    query("SELECT id, account_id, name, payload_json, updated_pulse FROM players ORDER BY id") {
      PlayerRow(
        it.getLong(1),
        it.getLong(2).takeUnless { _ -> it.wasNull() },
        it.getString(3),
        it.getString(4),
        it.getLong(5),
      )
    }

  fun zoneStates(): Map<String, String> =
    query("SELECT package, payload_json FROM zone_states") { it.getString(1) to it.getString(2) }
      .toMap()

  fun standings(): List<StandingRow> =
    query("SELECT holder, subject, amount, updated_pulse, history_json FROM standings") {
      StandingRow(it.getString(1), it.getString(2), it.getInt(3), it.getLong(4), it.getString(5))
    }

  fun itemEvents(itemId: Long): List<ItemEventRow> =
    query(
      "SELECT item_id, pulse, wall_time, from_json, to_json, cause FROM item_events WHERE item_id = $itemId ORDER BY id"
    ) {
      ItemEventRow(
        it.getLong(1),
        it.getLong(2),
        it.getLong(3),
        it.getString(4),
        it.getString(5),
        it.getString(6),
      )
    }

  fun diagnostics(): List<DiagnosticRow> =
    query("SELECT * FROM diagnostics ORDER BY id") { r ->
      DiagnosticRow(
        r.getLong("id"),
        r.getLong("pulse"),
        r.getLong("wall_time"),
        r.getString("package"),
        r.getString("label"),
        r.getString("hook"),
        r.getString("file"),
        r.getInt("line").takeUnless { r.wasNull() },
        r.getString("kind"),
        r.getString("message"),
        r.getString("stack"),
        r.getString("ctx_json"),
        r.getLong("seed").takeUnless { r.wasNull() },
        r.getString("repro_json"),
      )
    }

  fun diagnostic(id: Long): DiagnosticRow? = diagnostics().firstOrNull { it.id == id }

  /**
   * Applies one checkpoint batch in a single transaction, then completes the batch's futures:
   * [Batch.done] and the results of its account operations.
   */
  fun write(b: Batch) {
    val results = mutableListOf<() -> Unit>()
    transaction {
      writeAccounts(b.accounts, results)
      writeWorld(b)
    }
    results.forEach { it() }
    b.done.complete(Unit)
  }

  /**
   * Account changes. Conflicts are reported through results, never thrown, so a taken login can't
   * wedge the writer.
   */
  private fun writeAccounts(ops: List<AccountOp>, results: MutableList<() -> Unit>) {
    for (op in ops) {
      when (op) {
        is AccountOp.Create ->
          prepare(
            "INSERT OR IGNORE INTO accounts (login, pw_hash, roles_json, github_handle, disabled, disabled_by, disabled_at, disabled_reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
          ) { st ->
            val a = op.account
            st.setString(1, a.login)
            st.setString(2, a.pwHash)
            st.setString(3, rolesJson(a))
            st.setString(4, a.githubHandle)
            st.setInt(5, if (a.disabled) 1 else 0)
            val by = a.disabledBy
            if (by == null) st.setNull(6, java.sql.Types.INTEGER) else st.setLong(6, by)
            val at = a.disabledAt
            if (at == null) st.setNull(7, java.sql.Types.INTEGER) else st.setLong(7, at)
            st.setString(8, a.disabledReason)
            st.setLong(9, a.createdAt)
            val id = if (st.executeUpdate() == 1) scalar("SELECT last_insert_rowid()") else null
            results += { op.result.complete(id) }
          }
        is AccountOp.Update ->
          // Update writes only these columns; creations is not written so a stale Account copy can
          // never lower the counter.
          prepare(
            "UPDATE accounts SET roles_json = ?, github_handle = ?, disabled = ?, disabled_by = ?, disabled_at = ?, disabled_reason = ? WHERE id = ?"
          ) { st ->
            val a = op.account
            st.setString(1, rolesJson(a))
            st.setString(2, a.githubHandle)
            st.setInt(3, if (a.disabled) 1 else 0)
            val by = a.disabledBy
            if (by == null) st.setNull(4, java.sql.Types.INTEGER) else st.setLong(4, by)
            val at = a.disabledAt
            if (at == null) st.setNull(5, java.sql.Types.INTEGER) else st.setLong(5, at)
            st.setString(6, a.disabledReason)
            st.setLong(7, a.id)
            st.executeUpdate()
          }
        is AccountOp.CountCreation ->
          prepare("UPDATE accounts SET creations = creations + 1 WHERE id = ?") { st ->
            st.setLong(1, op.id)
            st.executeUpdate()
          }
        is AccountOp.SetPassword -> {
          prepare("UPDATE accounts SET pw_hash = ? WHERE id = ?") { st ->
            st.setString(1, op.pwHash)
            st.setLong(2, op.id)
            st.executeUpdate()
          }
          prepare("DELETE FROM session_tokens WHERE account_id = ?") { st ->
            st.setLong(1, op.id)
            st.executeUpdate()
          }
        }
        is AccountOp.AddInvite ->
          prepare(
            "INSERT OR IGNORE INTO invites (code_hash, role, expires_at, used_by, account_id) VALUES (?, ?, ?, NULL, ?)"
          ) { st ->
            st.setString(1, op.invite.codeHash)
            st.setString(2, op.invite.role.name)
            st.setLong(3, op.invite.expiresAt)
            val bound = op.invite.accountId
            if (bound == null) st.setNull(4, java.sql.Types.INTEGER) else st.setLong(4, bound)
            st.executeUpdate()
          }
        is AccountOp.RevokeTokens ->
          prepare("DELETE FROM session_tokens WHERE account_id = ?") { st ->
            st.setLong(1, op.id)
            st.executeUpdate()
          }
        is AccountOp.AddToken -> {
          prepare("DELETE FROM session_tokens WHERE expires_at <= ?") { st ->
            st.setLong(1, op.now)
            st.executeUpdate()
          }
          prepare(
            "INSERT OR REPLACE INTO session_tokens (token_hash, account_id, expires_at) VALUES (?, ?, ?)"
          ) { st ->
            st.setString(1, op.tokenHash)
            st.setLong(2, op.accountId)
            st.setLong(3, op.expiresAt)
            st.executeUpdate()
          }
        }
        is AccountOp.RemoveToken ->
          prepare("DELETE FROM session_tokens WHERE token_hash = ?") { st ->
            st.setString(1, op.tokenHash)
            st.executeUpdate()
          }
        is AccountOp.AddRestriction ->
          prepare(
            "INSERT INTO restrictions (account_id, capability, until, reason, set_by, created_at) VALUES (?, ?, ?, ?, ?, ?)"
          ) { st ->
            val r = op.restriction
            st.setLong(1, r.accountId)
            st.setString(2, r.capability)
            st.setObject(3, r.until)
            st.setString(4, r.reason)
            st.setObject(5, r.setBy)
            st.setLong(6, r.createdAt)
            st.executeUpdate()
            val id = scalar("SELECT last_insert_rowid()")
            results += { op.result.complete(id) }
          }
        is AccountOp.LiftRestriction ->
          prepare(
            "UPDATE restrictions SET lifted_by = ?, lifted_at = ? WHERE id = ? AND lifted_at IS NULL"
          ) { st ->
            st.setObject(1, op.by)
            st.setLong(2, op.at)
            st.setLong(3, op.id)
            st.executeUpdate()
          }
        is AccountOp.UseInvite ->
          prepare("UPDATE invites SET used_by = ? WHERE code_hash = ? AND used_by IS NULL") { st ->
            st.setLong(1, op.accountId)
            st.setString(2, op.codeHash)
            val used = st.executeUpdate() == 1
            results += { op.result.complete(used) }
          }
        is AccountOp.SetDisabled -> {
          var changed = false
          prepare("SELECT disabled FROM accounts WHERE id = ?") { st ->
            st.setLong(1, op.id)
            st.executeQuery().use { rs ->
              if (rs.next()) {
                val current = rs.getInt(1) != 0
                if (current != op.disabled) {
                  changed = true
                }
              }
            }
          }
          if (changed) {
            if (op.disabled) {
              prepare(
                "UPDATE accounts SET disabled = 1, disabled_by = ?, disabled_at = ?, disabled_reason = ? WHERE id = ?"
              ) { st ->
                if (op.by == null) st.setNull(1, java.sql.Types.INTEGER) else st.setLong(1, op.by)
                st.setLong(2, op.at)
                st.setString(3, op.reason)
                st.setLong(4, op.id)
                st.executeUpdate()
              }
              prepare("DELETE FROM session_tokens WHERE account_id = ?") { st ->
                st.setLong(1, op.id)
                st.executeUpdate()
              }
              prepare("DELETE FROM invites WHERE account_id = ? AND used_by IS NULL") { st ->
                st.setLong(1, op.id)
                st.executeUpdate()
              }
            } else {
              prepare(
                "UPDATE accounts SET disabled = 0, disabled_by = NULL, disabled_at = NULL, disabled_reason = NULL WHERE id = ?"
              ) { st ->
                st.setLong(1, op.id)
                st.executeUpdate()
              }
            }
          }
          results += { op.result.complete(changed) }
        }
      }
    }
  }

  private fun scalar(sql: String): Long =
    conn.createStatement().use { st ->
      st.executeQuery(sql).use {
        it.next()
        it.getLong(1)
      }
    }

  private fun writeWorld(b: Batch) {
    prepare("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)") { st ->
      for ((k, v) in b.meta) {
        st.setString(1, k)
        st.setString(2, v)
        st.addBatch()
      }
      st.executeBatch()
    }
    prepare("INSERT OR REPLACE INTO world_entities VALUES (?, ?, ?, ?, ?, ?, ?)") { up ->
      prepare("DELETE FROM world_entities WHERE id = ?") { del ->
        for ((id, row) in b.entities) {
          if (row == null) {
            del.setLong(1, id)
            del.addBatch()
          } else {
            up.setLong(1, row.id)
            up.setString(2, row.kind)
            up.setString(3, row.protoLabel)
            up.setInt(4, row.protoVersion)
            up.setString(5, row.location)
            up.setString(6, row.payload)
            up.setLong(7, row.updatedPulse)
            up.addBatch()
          }
        }
        del.executeBatch()
        up.executeBatch()
      }
    }
    prepare("INSERT OR REPLACE INTO players VALUES (?, ?, ?, ?, ?)") { st ->
      for (row in b.players.values) {
        st.setLong(1, row.id)
        if (row.accountId == null) st.setNull(2, java.sql.Types.INTEGER)
        else st.setLong(2, row.accountId)
        st.setString(3, row.name)
        st.setString(4, row.payload)
        st.setLong(5, row.updatedPulse)
        st.addBatch()
      }
      st.executeBatch()
    }
    prepare("INSERT OR REPLACE INTO zone_states VALUES (?, ?)") { st ->
      for ((pkg, json) in b.zones) {
        st.setString(1, pkg)
        st.setString(2, json)
        st.addBatch()
      }
      st.executeBatch()
    }
    b.standings?.let { standings ->
      conn.createStatement().use { st -> st.executeUpdate("DELETE FROM standings") }
      prepare(
        "INSERT INTO standings (holder, subject, amount, updated_pulse, history_json) VALUES (?, ?, ?, ?, ?)"
      ) { st ->
        for (row in standings) {
          st.setString(1, row.holder)
          st.setString(2, row.subject)
          st.setInt(3, row.amount)
          st.setLong(4, row.updatedPulse)
          st.setString(5, row.historyJson)
          st.addBatch()
        }
        st.executeBatch()
      }
    }
    prepare(
      "INSERT INTO item_events (item_id, pulse, wall_time, from_json, to_json, cause) VALUES (?, ?, ?, ?, ?, ?)"
    ) { st ->
      for (e in b.events) {
        st.setLong(1, e.itemId)
        st.setLong(2, e.pulse)
        st.setLong(3, e.wallTime)
        st.setString(4, e.fromJson)
        st.setString(5, e.toJson)
        st.setString(6, e.cause)
        st.addBatch()
      }
      st.executeBatch()
    }
    prepare(
      """INSERT INTO diagnostics (pulse, wall_time, package, label, hook, file, line, kind, message, stack, ctx_json, seed, repro_json)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
    ) { st ->
      for (d in b.diagnostics) {
        st.setLong(1, d.pulse)
        st.setLong(2, d.wallTime)
        st.setString(3, d.pkg)
        st.setString(4, d.label)
        st.setString(5, d.hook)
        st.setString(6, d.file)
        if (d.line == null) st.setNull(7, java.sql.Types.INTEGER) else st.setInt(7, d.line)
        st.setString(8, d.kind)
        st.setString(9, d.message)
        st.setString(10, d.stack)
        st.setString(11, d.ctxJson)
        if (d.seed == null) st.setNull(12, java.sql.Types.INTEGER) else st.setLong(12, d.seed)
        st.setString(13, d.reproJson)
        st.addBatch()
      }
      st.executeBatch()
    }
  }

  /** A consistent snapshot through SQLite's online-backup API, safe while the world writes. */
  fun backupTo(target: Path) = backup(file, target)

  /**
   * A copy of this database at [target] with nothing personal in it: `accounts`, `session_tokens`,
   * `invites`, `restrictions`, `diagnostics` and `item_events` emptied, characters renamed
   * `Player1`, `Player2`, ... by id and detached from accounts.
   */
  fun sanitizedCopy(target: Path): Path = sanitizedCopy(file, target)

  /** The time the snapshot was taken, recorded in `meta.snapshot_taken_at`. */
  fun snapshotTakenAt(): java.time.Instant? =
    meta()["snapshot_taken_at"]?.toLongOrNull()?.let { java.time.Instant.ofEpochSecond(it) }

  override fun close() = conn.close()

  private fun <T> query(sql: String, row: (java.sql.ResultSet) -> T): List<T> =
    conn.createStatement().use { st ->
      st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } }
    }

  private fun prepare(sql: String, block: (PreparedStatement) -> Unit) =
    conn.prepareStatement(sql).use(block)

  private fun transaction(block: () -> Unit) {
    conn.autoCommit = false
    try {
      block()
      conn.commit()
    } catch (e: Exception) {
      runCatching { conn.rollback() }
      throw e
    } finally {
      conn.autoCommit = true
    }
  }

  companion object {
    private fun url(file: Path) = "jdbc:sqlite:${file.absolutePathString()}"

    /**
     * Copies [source] to [target] through SQLite's online-backup API on a plain connection (no
     * migrations run), including any data in `world.db-wal`.
     */
    fun backup(source: Path, target: Path) {
      require(Files.isRegularFile(source)) { "No database at $source" }
      target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
      Files.deleteIfExists(target)
      DriverManager.getConnection(url(source)).use { c ->
        c.createStatement().use {
          it.executeUpdate("backup to '${target.absolutePathString().replace("'", "''")}'")
        }
      }
    }

    /**
     * Copies [source] to [target] via [backup] and sanitizes [target] in place without running
     * schema migrations on [source].
     */
    fun sanitizedCopy(source: Path, target: Path): Path {
      backup(source, target)
      try {
        sanitizeInPlace(target)
      } catch (e: Exception) {
        runCatching { Files.deleteIfExists(target) }
        throw e
      }
      return target
    }

    /**
     * Sanitizes a copy of a database in place at [target] without opening it through
     * [Database.open] (so no migrations run). Empties personal tables, renames players, and records
     * `snapshot_taken_at`.
     */
    fun sanitizeInPlace(target: Path) {
      DriverManager.getConnection(url(target)).use { c ->
        if (readSchemaVersion(c) == 0)
          throw SQLException("$target is not an initialized GUPS database")
        c.autoCommit = false
        c.createStatement().use { st ->
          val existingTables =
            c.createStatement().use { q ->
              q.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
              }
            }
          for (t in
            listOf(
              "accounts",
              "session_tokens",
              "invites",
              "restrictions",
              "diagnostics",
              "item_events",
            )) {
            if (t in existingTables) st.executeUpdate("DELETE FROM $t")
          }
          // Two steps, so the new names never collide with old ones under UNIQUE.
          st.executeUpdate("UPDATE players SET name = '#' || id, account_id = NULL")
          st.executeUpdate(
            "UPDATE players SET name = 'Player' || (SELECT COUNT(*) FROM players p WHERE p.id <= players.id)"
          )
          val now = java.time.Instant.now().epochSecond
          st.executeUpdate(
            "INSERT OR REPLACE INTO meta (key, value) VALUES ('snapshot_taken_at', '$now')"
          )
        }
        c.commit()
        c.autoCommit = true
        c.createStatement().use { it.execute("VACUUM") }
      }
    }

    private fun readSchemaVersion(conn: Connection): Int {
      val hasMeta =
        conn.createStatement().use { st ->
          st
            .executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'")
            .use { it.next() }
        }
      if (!hasMeta) return 0
      return conn.createStatement().use { st ->
        st.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'").use {
          if (it.next()) it.getString(1).toInt() else 0
        }
      }
    }

    /**
     * Opens [file], creating it if needed, and runs every migration it hasn't seen, each in its own
     * transaction. A database from a newer engine is refused.
     */
    fun open(file: Path, migrations: List<Migration> = MIGRATIONS): Database {
      file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
      val conn = DriverManager.getConnection(url(file))
      try {
        conn.createStatement().use { st ->
          st.execute("PRAGMA journal_mode = WAL")
          st.execute("PRAGMA synchronous = NORMAL")
          st.execute("PRAGMA busy_timeout = 5000")
        }
        val current = readSchemaVersion(conn)
        val latest = migrations.maxOfOrNull { it.version } ?: 0
        if (current > latest)
          throw SQLException(
            "$file has schema version $current; this engine knows up to $latest. Use a newer engine."
          )
        for (m in migrations.sortedBy { it.version }.filter { it.version > current }) {
          conn.autoCommit = false
          try {
            conn.createStatement().use { st ->
              for (sql in m.statements) st.execute(sql)
              st.execute(
                "INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '${m.version}')"
              )
            }
            conn.commit()
          } catch (e: SQLException) {
            conn.rollback()
            throw SQLException("schema migration ${m.version} failed: ${e.message}", e)
          } finally {
            conn.autoCommit = true
          }
        }
        return Database(file, conn)
      } catch (e: Exception) {
        conn.close()
        throw e
      }
    }

    /** Replaces [target]'s contents with [backup] (`gups restore`). The server must be stopped. */
    fun restore(backup: Path, target: Path) {
      require(Files.isRegularFile(backup)) { "No backup at $backup" }
      target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
      DriverManager.getConnection(url(target)).use { c ->
        c.createStatement().use {
          it.executeUpdate("restore from '${backup.absolutePathString().replace("'", "''")}'")
        }
      }
    }
  }
}
