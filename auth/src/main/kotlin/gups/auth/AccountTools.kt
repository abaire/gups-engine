package gups.auth

import gups.core.Account
import gups.core.Capabilities
import gups.core.Restriction
import gups.core.Role
import gups.core.model.Player
import gups.core.world.CommandCall
import gups.core.world.FastCommand
import gups.core.world.World
import gups.core.world.flush
import gups.core.world.later
import gups.core.world.send
import gups.core.world.session
import gups.core.world.wallTime

/** The default escalation ladder of `@restrict`. */
val DEFAULT_LADDER = listOf("1d", "7d", "permanent")

private val DURATION = Regex("(\\d{1,6})([mhdw])")

/**
 * A restriction length such as `30m`, `1d` or `2w` in milliseconds, null for `permanent`, or
 * [Result.failure].
 */
fun parseDuration(text: String): Result<Long?> {
  if (text.equals("permanent", ignoreCase = true)) return Result.success(null)
  val m =
    DURATION.matchEntire(text.lowercase())
      ?: return Result.failure(
        IllegalArgumentException("'$text' is not a length such as 30m, 1d, 7d or permanent")
      )
  val n = m.groupValues[1].toLong()
  val unit =
    when (m.groupValues[2]) {
      "m" -> 60_000L
      "h" -> 3_600_000L
      "d" -> 86_400_000L
      else -> 7 * 86_400_000L
    }
  return Result.success(n * unit)
}

/** Puts [a]'s role and builder scope on its characters in the world. */
fun World.applyAccount(a: Account) {
  for (p in players) {
    if (p.accountId != a.id) continue
    p.role = a.role
    p.scope = a.packages
    p.idleExempt = a.role.atLeast(Role.INSTRUCTOR)
  }
}

/**
 * The instructor commands that change accounts: `@role`, `@invite`, `@restrict`, `@unrestrict` and
 * `@restrictions`. Every store call runs off the world thread through [later].
 */
class AccountTools
private constructor(
  private val accounts: AccountService,
  private val ladder: List<String>,
  /** The current CODEOWNERS, so `@role` can refuse hand scope for covered packages. */
  private val codeOwners: () -> CodeOwners,
) {
  private val store
    get() = accounts.store

  private fun reply(w: World, p: Player, text: String) {
    w.send(p, text)
    p.session?.let { w.session(it) }?.let { w.flush(it) }
  }

  /** Runs [work] off the world thread, then [then] on it; a failure is reported to [p]. */
  private fun <T> run(w: World, p: Player, work: () -> T, then: World.(T) -> Unit) =
    w.later(work) { r -> r.fold({ then(it) }) { reply(this, p, "That failed: ${it.message}") } }

  // ---- @role -------------------------------------------------------------------------------

  private fun role(c: CommandCall) {
    val p = c.actor as? Player ?: return
    val w = c.world
    val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty())
      return w.send(p, "Usage: @role <account> [<role> [packages] | reset | revoke]")
    val login = words[0].lowercase()
    run(w, p, { store.byLogin(login) }) { a ->
      if (a == null) return@run reply(this, p, "There is no account '$login'.")
      when (val what = words.getOrNull(1)?.lowercase()) {
        null -> reply(this, p, describe(a))
        "reset" -> reset(this, p, a)
        "revoke" ->
          run(this, p, { store.revokeTokens(a.id) }) {
            reply(this, p, "${a.login}'s web sessions are revoked.")
          }
        else -> setRole(this, p, a, what, words.drop(2))
      }
    }
  }

  private fun describe(a: Account): String = buildString {
    append("${a.login}: ${a.role.name.lowercase()}")
    if (a.disabled) append(", disabled")
    a.githubHandle?.let { append(", GitHub @$it") }
    if (a.role == Role.BUILDER || a.packages.isNotEmpty()) {
      append("\n  CODEOWNERS packages: ${a.scope.sorted().joinToString().ifEmpty { "none" }}")
      append("\n  Packages by hand: ${a.manualScope.sorted().joinToString().ifEmpty { "none" }}")
    }
    if (a.pwHash == null) append("\n  No password yet: they need a setup code.")
  }

  /** Clears the password, revokes web tokens and hands the instructor a new setup code. */
  private fun reset(w: World, p: Player, a: Account) {
    if (!mayManage(p, a)) return reply(w, p, "Only an admin can reset an admin.")
    run(
      w,
      p,
      {
        store.setPassword(a.id, null)
        accounts.createInvite(a.role, DEFAULT_INVITE_DAYS, accountId = a.id)
      },
    ) { code ->
      reply(
        this,
        p,
        "${a.login}'s password is cleared and their web sessions are revoked. Their new setup code is $code; it works once, within $DEFAULT_INVITE_DAYS days.",
      )
    }
  }

  private fun setRole(w: World, p: Player, a: Account, roleWord: String, scopeWords: List<String>) {
    val role =
      Role.entries.firstOrNull { it.name.equals(roleWord, ignoreCase = true) }
        ?: return reply(w, p, "'$roleWord' is not a role: player, builder, instructor or admin.")
    if (a.id == p.accountId) return reply(w, p, "You can't change your own role.")
    if (!mayManage(p, a) || (role == Role.ADMIN && !p.role.atLeast(Role.ADMIN)))
      return reply(w, p, "Only an admin can make or change admins.")
    val packages =
      scopeWords
        .flatMap { it.split(',') }
        .map { it.trim().removePrefix("//") }
        .filter { it.isNotEmpty() }
    val known = w.scripts.packages
    packages
      .firstOrNull { known.isNotEmpty() && it !in known }
      ?.let {
        return reply(w, p, "There is no package //$it.")
      }
    val owners = codeOwners()
    packages.firstOrNull(owners::covers)?.let {
      return reply(w, p, "//$it is owned through CODEOWNERS; change it there.")
    }
    val next =
      a.copy(
        role = role,
        manualScope =
          if (role.atLeast(Role.INSTRUCTOR)) emptyList()
          else if (packages.isEmpty()) a.manualScope else packages.distinct(),
      )
    run(w, p, { store.update(next) }) {
      applyAccount(next)
      reply(
        this,
        p,
        "${next.login} is now ${article(role)} ${role.name.lowercase()}" +
          if (role == Role.BUILDER)
            " with packages ${next.packages.joinToString().ifEmpty { "none" }}."
          else ".",
      )
    }
  }

  /** Instructors manage players, builders and instructors; only admins manage admins. */
  private fun mayManage(p: Player, a: Account) = a.role != Role.ADMIN || p.role.atLeast(Role.ADMIN)

  private fun article(r: Role) = if (r == Role.INSTRUCTOR || r == Role.ADMIN) "an" else "a"

  // ---- @invite -----------------------------------------------------------------------------

  private fun invite(c: CommandCall) {
    val p = c.actor as? Player ?: return
    val w = c.world
    val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val role =
      words.firstOrNull()?.let { r ->
        Role.entries.firstOrNull { it.name.equals(r, ignoreCase = true) }
      } ?: return w.send(p, "Usage: @invite <player|builder|instructor|admin> [days]")
    val days =
      words.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 1 }
        ?: if (words.size > 1) return w.send(p, "Days must be a number, 1 or more.")
        else DEFAULT_INVITE_DAYS
    if (role == Role.ADMIN && !p.role.atLeast(Role.ADMIN))
      return w.send(p, "Only an admin can invite an admin.")
    run(w, p, { accounts.createInvite(role, days) }) { code ->
      reply(
        this,
        p,
        "Invite code $code makes one new ${role.name.lowercase()} account within $days days.",
      )
    }
  }

  // ---- Restrictions ------------------------------------------------------------------

  private fun restrict(c: CommandCall) {
    val p = c.actor as? Player ?: return
    val w = c.world
    val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size < 2)
      return w.send(p, "Usage: @restrict <account> <capability> [30m|1d|7d|permanent] [reason]")
    val login = words[0].lowercase()
    val capability = words[1].lowercase()
    if (capability !in Capabilities.ALL)
      return w.send(p, "'$capability' is not a capability: ${Capabilities.ALL.joinToString()}.")
    val explicit = words.getOrNull(2)?.let { parseDuration(it) }?.takeIf { it.isSuccess }
    val reason = words.drop(if (explicit != null) 3 else 2).joinToString(" ").ifEmpty { null }
    val now = w.wallClock()
    run(
      w,
      p,
      {
        val a = store.byLogin(login) ?: return@run null to "There is no account '$login'."
        if (a.role.atLeast(Role.INSTRUCTOR))
          return@run null to "Instructors and admins can't be restricted."
        val history = store.restrictions(a.id).filter { it.capability == capability }
        // Lifted and expired restrictions still count toward the ladder.
        val step = ladder.getOrElse(history.size) { ladder.last() }
        val length = explicit?.getOrThrow() ?: parseDuration(step).getOrThrow()
        val r =
          store.addRestriction(
            Restriction(0, a.id, capability, length?.let { now + it }, reason, p.accountId, now)
          )
        r to
          "${a.login} can't use $capability " +
            (r.until?.let { "until ${wallTime(it)}" } ?: "until an instructor lifts it") +
            (if (explicit == null) " (restriction ${history.size + 1}: $step)" else "") +
            (reason?.let { ": $it" } ?: "") +
            "."
      },
    ) { (r, message) ->
      if (r != null) restrictions = restrictions + r
      reply(this, p, message)
    }
  }

  private fun unrestrict(c: CommandCall) {
    val p = c.actor as? Player ?: return
    val w = c.world
    val words = c.args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.size != 2) return w.send(p, "Usage: @unrestrict <account> <capability>")
    val (login, capability) = words[0].lowercase() to words[1].lowercase()
    val now = w.wallClock()
    run(
      w,
      p,
      {
        val a = store.byLogin(login) ?: return@run null
        val active =
          store.restrictions(a.id).filter { it.capability == capability && it.activeAt(now) }
        for (r in active) store.liftRestriction(r.id, p.accountId, now)
        Triple(a, active.size, store.restrictions(a.id))
      },
    ) { result ->
      val (a, lifted, fresh) = result ?: return@run reply(this, p, "There is no account '$login'.")
      restrictions = restrictions.filter { it.accountId != a.id } + fresh
      reply(
        this,
        p,
        if (lifted == 0) "${a.login} has no active restriction on $capability."
        else "${a.login} may use $capability again.",
      )
    }
  }

  private fun list(c: CommandCall) {
    val p = c.actor as? Player ?: return
    val w = c.world
    val login = c.args.trim().lowercase().ifEmpty { null }
    val now = w.wallClock()
    run(
      w,
      p,
      {
        val all = store.all().associate { it.id to it.login }
        val target =
          login?.let { store.byLogin(it) ?: return@run listOf("There is no account '$it'.") }
        val rows =
          if (target != null) store.restrictions(target.id)
          else store.restrictions().filter { it.activeAt(now) }
        if (rows.isEmpty())
          return@run listOf(
            if (target != null) "${target.login} has never been restricted."
            else "No restrictions are in effect."
          )
        listOf(
          if (target != null) "${target.login}'s restrictions, oldest first:"
          else "Restrictions in effect:"
        ) +
          rows.map { r ->
            val (lifted, until) = r.liftedAt to r.until
            val state =
              when {
                lifted != null ->
                  "lifted by ${r.liftedBy?.let(all::get) ?: "?"} ${wallTime(lifted)}"
                until == null -> "permanent"
                until > now -> "until ${wallTime(until)}"
                else -> "ended ${wallTime(until)}"
              }
            "  ${all[r.accountId] ?: "#${r.accountId}"} ${r.capability}: $state; set by ${r.setBy?.let(all::get) ?: "?"} ${wallTime(r.createdAt)}" +
              (r.reason?.let { ": $it" } ?: "")
          }
      },
    ) { lines ->
      reply(this, p, lines.joinToString("\n"))
    }
  }

  companion object {
    /** Registers the account commands with [world]. */
    fun install(
      world: World,
      accounts: AccountService,
      ladder: List<String> = DEFAULT_LADDER,
      codeOwners: () -> CodeOwners = { CodeOwners.EMPTY },
    ): AccountTools {
      require(ladder.isNotEmpty() && ladder.all { parseDuration(it).isSuccess }) {
        "restriction_ladder: every step must be a length such as 1d or permanent"
      }
      val t = AccountTools(accounts, ladder, codeOwners)
      val r = world.commands
      r.registerTool(FastCommand("@role", role = Role.INSTRUCTOR) { t.role(it) })
      r.registerTool(FastCommand("@invite", role = Role.INSTRUCTOR) { t.invite(it) })
      r.registerTool(FastCommand("@restrict", role = Role.INSTRUCTOR) { t.restrict(it) })
      r.registerTool(FastCommand("@unrestrict", role = Role.INSTRUCTOR) { t.unrestrict(it) })
      r.registerTool(FastCommand("@restrictions", role = Role.INSTRUCTOR) { t.list(it) })
      return t
    }
  }
}
