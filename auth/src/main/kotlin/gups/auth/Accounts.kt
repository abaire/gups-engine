package gups.auth

import gups.core.Account
import gups.core.AccountStore
import gups.core.Invite
import gups.core.Role

/** Invites and setup codes last a week unless asked otherwise. */
const val DEFAULT_INVITE_DAYS = 7

private const val DAY_MS = 24 * 60 * 60_000L

data class RosterEntry(
  val login: String,
  val githubHandle: String? = null,
  val role: Role = Role.PLAYER,
)

/**
 * What a roster import did. [codes] holds `login` to setup code for each new account, in roster
 * order.
 */
data class RosterResult(val codes: List<Pair<String, String>>, val updated: Int, val unchanged: Int)

/** Redeeming a code: the account it set up or created, or why it failed. */
sealed interface Redeemed {
  data class Ok(val account: Account) : Redeemed

  /** The code is unknown, used or expired. */
  data object BadCode : Redeemed

  /** Someone took the login between the prompt and now. */
  data object LoginTaken : Redeemed
}

/**
 * Account operations shared by the login flow and the CLI tools. Every call blocks on [store] and
 * may hash, so callers keep it off the world thread.
 */
class AccountService(
  val store: AccountStore,
  val hasher: PasswordHasher = Argon2Hasher(),
  /** Wall-clock milliseconds; invites expire by it. */
  private val clock: () -> Long = System::currentTimeMillis,
  private val newCode: () -> String = { Codes.generate() },
) {
  /**
   * Redemption is check-then-use, so one at a time in this process; the store's `useInvite` is the
   * final word.
   */
  private val redeeming = Any()

  /**
   * Makes an invite for [role] (bound to [accountId] for a setup code) and returns its code. Only
   * the hash is stored.
   */
  fun createInvite(role: Role, days: Int = DEFAULT_INVITE_DAYS, accountId: Long? = null): String {
    require(days >= 1) { "an invite lasts at least a day" }
    val code = newCode()
    store.addInvite(Invite(Codes.hash(code), role, clock() + days * DAY_MS, accountId))
    return code
  }

  /** The unused, unexpired invite with this code, or null. */
  fun validInvite(code: String): Invite? {
    val canonical = Codes.normalize(code) ?: return null
    return store.invite(Codes.hash(canonical))?.takeIf {
      it.usedBy == null && it.expiresAt > clock()
    }
  }

  /**
   * Uses a code: a setup code gets [password] on its account, any other invite creates [login] with
   * the invite's role. With [code] null (open signup) a player account is created without one.
   */
  fun redeem(code: String?, login: String, password: String): Redeemed {
    val hash = hasher.hash(password)
    return synchronized(redeeming) { redeemHashed(code, login, hash) }
  }

  private fun redeemHashed(code: String?, login: String, hash: String): Redeemed {
    val invite = code?.let { validInvite(it) ?: return Redeemed.BadCode }
    val bound = invite?.accountId
    val account =
      if (bound != null) {
        val a = store.byId(bound) ?: return Redeemed.BadCode
        if (!store.useInvite(invite.codeHash, a.id)) return Redeemed.BadCode
        store.setPassword(a.id, hash)
        a.copy(pwHash = hash)
      } else {
        if (store.byLogin(login) != null) return Redeemed.LoginTaken
        val a =
          store.create(Account(0, login, hash, invite?.role ?: Role.PLAYER, createdAt = clock()))
            ?: return Redeemed.LoginTaken
        // Another process used the code first: the new account is shut rather than deleted.
        if (invite != null && !store.useInvite(invite.codeHash, a.id)) {
          store.update(a.copy(role = Role.PLAYER, disabled = true))
          return Redeemed.BadCode
        }
        a
      }
    return Redeemed.Ok(account)
  }

  /**
   * `gups import-roster`: new logins become password-less accounts with a setup code each; existing
   * ones get the roster's GitHub handle and role, and keep their password. Running the same roster
   * again changes nothing and issues no codes.
   */
  fun importRoster(entries: List<RosterEntry>, days: Int = DEFAULT_INVITE_DAYS): RosterResult {
    val codes = mutableListOf<Pair<String, String>>()
    var updated = 0
    var unchanged = 0
    for (e in entries) {
      val existing = store.byLogin(e.login)
      if (existing == null) {
        val a =
          store.create(
            Account(0, e.login, null, e.role, githubHandle = e.githubHandle, createdAt = clock())
          ) ?: error("login ${e.login} was taken during the import")
        codes += e.login to createInvite(e.role, days, accountId = a.id)
      } else if (existing.githubHandle != e.githubHandle || existing.role != e.role) {
        store.update(existing.copy(githubHandle = e.githubHandle, role = e.role))
        updated++
      } else {
        unchanged++
      }
    }
    return RosterResult(codes, updated, unchanged)
  }
}

/** Parses a roster CSV. */
object Roster {
  /**
   * A header row naming the columns, any order: `login` (required), `github_handle` and `role`
   * (default `player`). Blank lines and `#` comments are skipped; fields may be double-quoted. Any
   * bad row fails the whole roster, with every problem listed.
   */
  fun parse(text: String): Pair<List<RosterEntry>, List<String>> {
    val lines =
      text.lines().withIndex().filter { (_, l) -> l.isNotBlank() && !l.trimStart().startsWith("#") }
    if (lines.isEmpty()) return emptyList<RosterEntry>() to listOf("the roster is empty")
    val header = fields(lines.first().value).map { it.lowercase() }
    val problems = mutableListOf<String>()
    val unknown = header.filter { it !in COLUMNS }
    if (unknown.isNotEmpty())
      problems +=
        "line ${lines.first().index + 1}: unknown column(s) ${unknown.joinToString()}; expected ${COLUMNS.joinToString(",")}"
    val loginAt = header.indexOf("login")
    if (loginAt < 0)
      return emptyList<RosterEntry>() to
        (problems + "line ${lines.first().index + 1}: the header has no 'login' column")
    val handleAt = header.indexOf("github_handle")
    val roleAt = header.indexOf("role")
    val seen = HashSet<String>()
    val out = mutableListOf<RosterEntry>()
    for ((i, line) in lines.drop(1)) {
      val n = i + 1
      val f = fields(line)
      if (f.size > header.size) {
        problems += "line $n: ${f.size} fields, but the header has ${header.size}"
        continue
      }
      val login = f.getOrNull(loginAt)?.lowercase().orEmpty()
      Names.loginProblem(login)?.let { problems += "line $n: '$login': $it" }
      if (!seen.add(login)) problems += "line $n: '$login' appears twice"
      val handle =
        f.getOrNull(handleAt)?.removePrefix("@")?.takeIf { handleAt >= 0 && it.isNotEmpty() }
      val roleText = f.getOrNull(roleAt)?.takeIf { roleAt >= 0 && it.isNotEmpty() } ?: "player"
      val role = Role.entries.firstOrNull { it.name.equals(roleText, ignoreCase = true) }
      if (role == null)
        problems +=
          "line $n: unknown role '$roleText'; expected ${Role.entries.joinToString { it.name.lowercase() }}"
      out += RosterEntry(login, handle, role ?: Role.PLAYER)
    }
    return (if (problems.isEmpty()) out else emptyList()) to problems
  }

  private val COLUMNS = listOf("login", "github_handle", "role")

  /** Splits one CSV line; `""` inside quotes is a quote. */
  private fun fields(line: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
      val c = line[i]
      when {
        quoted && c == '"' && line.getOrNull(i + 1) == '"' -> {
          cur.append('"')
          i++
        }
        c == '"' -> quoted = !quoted
        c == ',' && !quoted -> {
          out += cur.toString().trim()
          cur.setLength(0)
        }
        else -> cur.append(c)
      }
      i++
    }
    out += cur.toString().trim()
    return out
  }
}
