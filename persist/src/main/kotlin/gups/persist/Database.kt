package gups.persist

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import kotlin.io.path.absolutePathString

/** One schema step (SPEC §5.11 startup step 1). Versions are consecutive from 1. */
class Migration(val version: Int, val statements: List<String>)

/** The engine's schema (SPEC §3.6). Append new migrations; never edit applied ones. */
val MIGRATIONS: List<Migration> = listOf(
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
            """CREATE TABLE item_events (id INTEGER PRIMARY KEY AUTOINCREMENT, item_id INTEGER NOT NULL, pulse INTEGER NOT NULL,
                wall_time INTEGER NOT NULL, from_json TEXT, to_json TEXT, cause TEXT NOT NULL)""",
            "CREATE INDEX item_events_item ON item_events (item_id)",
            """CREATE TABLE diagnostics (id INTEGER PRIMARY KEY AUTOINCREMENT, pulse INTEGER NOT NULL, wall_time INTEGER NOT NULL,
                package TEXT, label TEXT, hook TEXT, file TEXT, line INTEGER, kind TEXT NOT NULL, message TEXT NOT NULL,
                stack TEXT, ctx_json TEXT, seed INTEGER, repro_json TEXT)""",
        ),
    ),
)

/** A diagnostics row (SPEC §3.6). */
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

/** An `item_events` row (SPEC §3.6). */
data class ItemEventRow(val itemId: Long, val pulse: Long, val wallTime: Long, val fromJson: String?, val toJson: String?, val cause: String)

/**
 * The SQLite database in WAL mode (SPEC §1.5, §3.6). One instance owns one connection; the
 * checkpoint writer is the only thing that writes through it once the world runs.
 */
class Database private constructor(val file: Path, private val conn: Connection) : AutoCloseable {
    val schemaVersion: Int get() = readSchemaVersion(conn)

    fun meta(): Map<String, String> = query("SELECT key, value FROM meta") { it.getString(1) to it.getString(2) }.toMap()

    fun entityRows(): List<EntityRow> = query(
        "SELECT id, kind, proto_label, proto_version, location_json, payload_json, updated_pulse FROM world_entities ORDER BY id",
    ) { EntityRow(it.getLong(1), it.getString(2), it.getString(3), it.getInt(4), it.getString(5), it.getString(6), it.getLong(7)) }

    fun playerRows(): List<PlayerRow> = query("SELECT id, account_id, name, payload_json, updated_pulse FROM players ORDER BY id") {
        PlayerRow(it.getLong(1), it.getLong(2).takeUnless { _ -> it.wasNull() }, it.getString(3), it.getString(4), it.getLong(5))
    }

    fun zoneStates(): Map<String, String> = query("SELECT package, payload_json FROM zone_states") { it.getString(1) to it.getString(2) }.toMap()

    fun itemEvents(itemId: Long): List<ItemEventRow> = query(
        "SELECT item_id, pulse, wall_time, from_json, to_json, cause FROM item_events WHERE item_id = $itemId ORDER BY id",
    ) { ItemEventRow(it.getLong(1), it.getLong(2), it.getLong(3), it.getString(4), it.getString(5), it.getString(6)) }

    fun diagnostics(): List<DiagnosticRow> = query("SELECT * FROM diagnostics ORDER BY id") { r ->
        DiagnosticRow(
            r.getLong("id"), r.getLong("pulse"), r.getLong("wall_time"), r.getString("package"), r.getString("label"),
            r.getString("hook"), r.getString("file"), r.getInt("line").takeUnless { r.wasNull() }, r.getString("kind"),
            r.getString("message"), r.getString("stack"), r.getString("ctx_json"), r.getLong("seed").takeUnless { r.wasNull() },
            r.getString("repro_json"),
        )
    }

    fun diagnostic(id: Long): DiagnosticRow? = diagnostics().firstOrNull { it.id == id }

    /** Applies one checkpoint batch in a single transaction (SPEC §5.11). */
    fun write(b: Batch) = transaction {
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
                if (row.accountId == null) st.setNull(2, java.sql.Types.INTEGER) else st.setLong(2, row.accountId)
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
        prepare("INSERT INTO item_events (item_id, pulse, wall_time, from_json, to_json, cause) VALUES (?, ?, ?, ?, ?, ?)") { st ->
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
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
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

    /** A consistent snapshot through SQLite's online-backup API, safe while the world writes (SPEC §5.11). */
    fun backupTo(target: Path) {
        Files.createDirectories(target.toAbsolutePath().parent)
        DriverManager.getConnection(url(file)).use { c -> c.createStatement().use { it.executeUpdate("backup to '${target.absolutePathString().replace("'", "''")}'") } }
    }

    override fun close() = conn.close()

    private fun <T> query(sql: String, row: (java.sql.ResultSet) -> T): List<T> = conn.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(row(rs)) } }
    }

    private fun prepare(sql: String, block: (PreparedStatement) -> Unit) = conn.prepareStatement(sql).use(block)

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

        private fun readSchemaVersion(conn: Connection): Int {
            val hasMeta = conn.createStatement().use { st ->
                st.executeQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'meta'").use { it.next() }
            }
            if (!hasMeta) return 0
            return conn.createStatement().use { st ->
                st.executeQuery("SELECT value FROM meta WHERE key = 'schema_version'").use { if (it.next()) it.getString(1).toInt() else 0 }
            }
        }

        /**
         * Opens [file], creating it if needed, and runs every migration it hasn't seen, each in
         * its own transaction. A database from a newer engine is refused.
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
                if (current > latest) throw SQLException("$file has schema version $current; this engine knows up to $latest. Use a newer engine.")
                for (m in migrations.sortedBy { it.version }.filter { it.version > current }) {
                    conn.autoCommit = false
                    try {
                        conn.createStatement().use { st ->
                            for (sql in m.statements) st.execute(sql)
                            st.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('schema_version', '${m.version}')")
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
                c.createStatement().use { it.executeUpdate("restore from '${backup.absolutePathString().replace("'", "''")}'") }
            }
        }
    }
}
