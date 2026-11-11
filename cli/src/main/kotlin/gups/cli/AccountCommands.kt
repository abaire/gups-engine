package gups.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import gups.auth.AccountService
import gups.auth.DEFAULT_INVITE_DAYS
import gups.auth.Roster
import gups.core.Role
import gups.persist.Database
import gups.persist.SqliteAccountStore
import java.io.File

/**
 * Runs [block] on the account store of the world's database, written directly: the CLI tools work
 * offline.
 */
private fun <T> withAccounts(world: File?, db: File?, block: (AccountService) -> T): T {
  val path = dbPath(world, db)
  return Database.open(path).use { d ->
    SqliteAccountStore(path, d::write).use { block(AccountService(it)) }
  }
}

private fun parseRole(text: String): Role =
  Role.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
    ?: throw UsageError(
      "--role: '$text' is not one of ${Role.entries.joinToString { it.name.lowercase() }}"
    )

class Invite : CliktCommand(name = "invite") {
  val role by
    option(
        "--role",
        metavar = "R",
        help = "Role granted by the invite: player, builder, instructor or admin.",
      )
      .required()
  val days by
    option(
        "--days",
        metavar = "N",
        help = "Days until the code expires (default $DEFAULT_INVITE_DAYS).",
      )
      .int()
      .default(DEFAULT_INVITE_DAYS)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val db by
    option("--db", help = "Database to add the invite to (default: the world's).")
      .file(canBeDir = false)

  override fun help(context: Context) = "Create an invite code."

  override fun run() {
    val r = parseRole(role)
    if (days < 1) throw UsageError("--days must be at least 1")
    val code = withAccounts(world, db) { it.createInvite(r, days) }
    echo(code)
    echo(
      "Invite for a new ${r.name.lowercase()} account; it works once and expires in $days days.",
      err = true,
    )
  }
}

class ImportRoster : CliktCommand(name = "import-roster") {
  val roster by argument("roster.csv").file(mustExist = true, canBeDir = false)
  val days by
    option(
        "--days",
        metavar = "N",
        help = "Days until the setup codes expire (default $DEFAULT_INVITE_DAYS).",
      )
      .int()
      .default(DEFAULT_INVITE_DAYS)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val db by
    option("--db", help = "Database to add the accounts to (default: the world's).")
      .file(canBeDir = false)
  val admin by
    option(
      "--admin",
      metavar = "ADDR",
      help = "Import through a running server's admin port instead, e.g. $DEFAULT_ADMIN.",
    )
  val adminTokenFile by
    option(
        "--admin-token-file",
        metavar = "FILE",
        help = "File holding the admin token (default: \$GUPS_ADMIN_TOKEN_FILE).",
      )
      .file(canBeDir = false)

  override fun help(context: Context) =
    "Create accounts from a roster CSV (columns login,github_handle,role,disabled) and print each new login's setup code."

  override fun run() {
    if (days < 1) throw UsageError("--days must be at least 1")
    val address = admin
    if (address != null) importThroughAdmin(address) else importOffline()
  }

  private fun importThroughAdmin(address: String) {
    if (days != DEFAULT_INVITE_DAYS)
      throw UsageError("--days works only offline; the admin port uses $DEFAULT_INVITE_DAYS days")
    val status =
      AdminClient(address, adminTokenFile).post("/roster", roster.readText(), "text/csv") {
        status,
        line ->
        echo(line, err = status != 200)
      }
    if (status != 200) throw ProgramResult(1)
  }

  private fun importOffline() {
    val (entries, problems) = Roster.parse(roster.readText())
    if (problems.isNotEmpty()) {
      problems.forEach { echo("${roster.name}: $it", err = true) }
      throw ProgramResult(1)
    }
    val result = withAccounts(world, db) { it.importRoster(entries, days) }
    echo("login,setup_code")
    for ((login, code) in result.codes) echo("$login,$code")
    echo(
      "${result.created} accounts created, ${result.updated} updated, ${result.unchanged} unchanged, ${result.disabled.size} disabled, ${result.enabled.size} enabled. Setup codes expire in $days days.",
      err = true,
    )
    if (result.disabled.isNotEmpty()) {
      echo(
        "Warning: characters already in the world were not quit; use --admin against a running server.",
        err = true,
      )
    }
  }
}
