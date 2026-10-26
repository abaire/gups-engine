package gups.persist

import gups.core.Account
import gups.core.AccountStore
import gups.core.Invite
import gups.core.Restriction
import gups.core.Role
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString
import kotlinx.serialization.Serializable
import org.sqlite.SQLiteConfig

/** `accounts.roles_json`: one role, and the builder scope CODEOWNERS fills. */
@Serializable
internal class RolesJson(
  val role: String,
  val scope: List<String> = emptyList(),
  val manual: List<String> = emptyList(),
)

internal fun rolesJson(a: Account): String =
  JSON.encodeToString(RolesJson.serializer(), RolesJson(a.role.name, a.scope, a.manualScope))

/** A read-only connection beside the writer, which WAL allows. */
internal fun readOnlyConnection(file: Path): Connection =
  SQLiteConfig()
    .apply {
      setReadOnly(true)
      busyTimeout = 5000
    }
    .createConnection("jdbc:sqlite:${file.absolutePathString()}")

/** How long an account write waits for the checkpoint writer before giving up. */
private const val WRITE_TIMEOUT_S = 30L

/**
 * SQLite account storage. Reads go to a read-only connection of their own, which WAL lets run
 * beside the writer. Writes are single-purpose [Batch]es handed to [write], which returns once the
 * batch is committed: through the [CheckpointWriter] in a running server, or [Database.write]
 * directly for the offline CLI tools.
 */
class SqliteAccountStore(file: Path, private val write: (Batch) -> Unit) :
  AccountStore, AutoCloseable {
  private val read: Connection = readOnlyConnection(file)

  override fun byLogin(login: String): Account? = accounts("WHERE login = ?", login).firstOrNull()

  override fun byId(id: Long): Account? = accounts("WHERE id = ?", id).firstOrNull()

  override fun all(): List<Account> = accounts("")

  override fun create(account: Account): Account? {
    val op = AccountOp.Create(account)
    submit(op)
    val id = op.result.get(WRITE_TIMEOUT_S, TimeUnit.SECONDS) ?: return null
    return account.copy(id = id)
  }

  override fun update(account: Account) = submit(AccountOp.Update(account))

  override fun setPassword(id: Long, pwHash: String?) = submit(AccountOp.SetPassword(id, pwHash))

  override fun addInvite(invite: Invite) = submit(AccountOp.AddInvite(invite))

  override fun invite(codeHash: String): Invite? =
    synchronized(read) {
      read
        .prepareStatement(
          "SELECT code_hash, role, expires_at, account_id, used_by FROM invites WHERE code_hash = ?"
        )
        .use { st ->
          st.setString(1, codeHash)
          st.executeQuery().use { rs ->
            if (!rs.next()) return null
            Invite(
              rs.getString(1),
              Role.valueOf(rs.getString(2)),
              rs.getLong(3),
              rs.nullableLong(4),
              rs.nullableLong(5),
            )
          }
        }
    }

  override fun useInvite(codeHash: String, accountId: Long): Boolean {
    val op = AccountOp.UseInvite(codeHash, accountId)
    submit(op)
    return op.result.get(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
  }

  override fun revokeTokens(id: Long) = submit(AccountOp.RevokeTokens(id))

  override fun restrictions(accountId: Long?): List<Restriction> =
    synchronized(read) {
      val where = if (accountId == null) "" else "WHERE account_id = ?"
      read
        .prepareStatement(
          "SELECT id, account_id, capability, until, reason, set_by, created_at, lifted_by, lifted_at FROM restrictions $where ORDER BY id"
        )
        .use { st ->
          if (accountId != null) st.setLong(1, accountId)
          st.executeQuery().use { rs ->
            buildList {
              while (rs.next()) {
                add(
                  Restriction(
                    rs.getLong(1),
                    rs.getLong(2),
                    rs.getString(3),
                    rs.nullableLong(4),
                    rs.getString(5),
                    rs.nullableLong(6),
                    rs.getLong(7),
                    rs.nullableLong(8),
                    rs.nullableLong(9),
                  )
                )
              }
            }
          }
        }
    }

  override fun addRestriction(r: Restriction): Restriction {
    val op = AccountOp.AddRestriction(r)
    submit(op)
    return r.copy(id = op.result.get(WRITE_TIMEOUT_S, TimeUnit.SECONDS))
  }

  override fun liftRestriction(id: Long, by: Long?, at: Long) =
    submit(AccountOp.LiftRestriction(id, by, at))

  override fun addToken(tokenHash: String, accountId: Long, expiresAt: Long, now: Long) =
    submit(AccountOp.AddToken(tokenHash, accountId, expiresAt, now))

  override fun tokenAccount(tokenHash: String, now: Long): Long? =
    synchronized(read) {
      read
        .prepareStatement(
          "SELECT account_id FROM session_tokens WHERE token_hash = ? AND expires_at > ?"
        )
        .use { st ->
          st.setString(1, tokenHash)
          st.setLong(2, now)
          st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
        }
    }

  override fun removeToken(tokenHash: String) = submit(AccountOp.RemoveToken(tokenHash))

  override fun close() = synchronized(read) { read.close() }

  private fun submit(op: AccountOp) {
    val b = Batch(immediate = true, cause = "account", firstPulse = 0, accounts = mutableListOf(op))
    write(b)
    b.done.get(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
  }

  private fun accounts(where: String, vararg args: Any): List<Account> =
    synchronized(read) {
      read
        .prepareStatement(
          "SELECT id, login, pw_hash, roles_json, github_handle, disabled, created_at FROM accounts $where ORDER BY id"
        )
        .use { st ->
          args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
          st.executeQuery().use { rs ->
            buildList {
              while (rs.next()) {
                val roles = JSON.decodeFromString(RolesJson.serializer(), rs.getString(4))
                add(
                  Account(
                    id = rs.getLong(1),
                    login = rs.getString(2),
                    pwHash = rs.getString(3),
                    role = Role.valueOf(roles.role),
                    scope = roles.scope,
                    manualScope = roles.manual,
                    githubHandle = rs.getString(5),
                    disabled = rs.getInt(6) != 0,
                    createdAt = rs.getLong(7),
                  )
                )
              }
            }
          }
        }
    }

  companion object {
    /** A store for a running server: writes go through [writer]. */
    fun through(file: Path, writer: CheckpointWriter) =
      SqliteAccountStore(file) { writer.submit(it) }
  }
}

private fun ResultSet.nullableLong(i: Int): Long? = getLong(i).takeUnless { wasNull() }
