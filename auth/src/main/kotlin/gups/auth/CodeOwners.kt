package gups.auth

import gups.core.Account
import gups.core.AccountStore
import gups.core.Role

/**
 * Parser and package ownership resolver for `CODEOWNERS` files adhering to GitHub specification
 * rules.
 *
 * ## Discovery & Precedence
 * - Evaluates file locations in GitHub precedence order: `.github/CODEOWNERS`, `CODEOWNERS`, and
 *   `docs/CODEOWNERS`.
 * - The first file found on disk is parsed; subsequent files are ignored.
 *
 * ## Rule Evaluation Semantics
 * - Lines specify a gitignore-style path pattern followed by zero or more owner tokens.
 * - A leading `/` or an inner slash anchors a pattern to the world root; otherwise the name matches
 *   at any depth. `*` stays within one directory and `**` crosses directories.
 * - File patterns such as `*.star` own no package. Inline `#` comments aren't supported, as on
 *   GitHub.
 * - A rule matches a package if its pattern matches the package's relative directory path or any
 *   ancestor directory.
 * - **Last-match-wins:** When multiple rules match a package, the last matching rule in the file
 *   determines ownership.
 * - **Unowned packages:** A rule line specifying a pattern with no owners explicitly clears
 *   ownership for matching paths.
 * - **Owner formats:** Only `@username` individual GitHub handles (normalized to lowercase without
 *   the leading `@`) confer ownership. Team owners (`@org/team`) and email addresses produce
 *   warnings and confer no package ownership.
 *
 * ## Builder Scopes
 * - Packages owned via `CODEOWNERS` automatically grant builder edit scope to matching GitHub
 *   account holders.
 * - Packages covered by `CODEOWNERS` cannot have builder scopes reassigned manually via the in-game
 *   `@role` command.
 */
class CodeOwners private constructor(private val rules: List<Rule>, val warnings: List<String>) {
  private class Rule(val line: Int, val pattern: String, val regex: Regex, val owners: List<String>)

  /** The GitHub handles (lowercase, no `@`) that own package [pkg], such as `zones/forest`. */
  fun owners(pkg: String): List<String> {
    val dirs = pkg.split('/').runningReduce { a, b -> "$a/$b" }
    return rules.lastOrNull { r -> dirs.any { r.regex.matches(it) } }?.owners ?: emptyList()
  }

  /** True if CODEOWNERS gives [pkg] an owner, so `@role` can't scope it by hand. */
  fun covers(pkg: String): Boolean = owners(pkg).isNotEmpty()

  /** For each owner handle, the packages among [packages] it owns. */
  fun scopes(packages: Collection<String>): Map<String, Set<String>> {
    val out = HashMap<String, MutableSet<String>>()
    for (pkg in packages) for (o in owners(pkg)) out.getOrPut(o) { sortedSetOf() } += pkg
    return out
  }

  companion object {
    /** File names GitHub reads, in its order of precedence; the first that exists is used. */
    val LOCATIONS = listOf(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS")

    val EMPTY = CodeOwners(emptyList(), emptyList())

    fun parse(text: String, file: String = "CODEOWNERS"): CodeOwners {
      val rules = mutableListOf<Rule>()
      val warnings = mutableListOf<String>()
      for ((i, raw) in text.lines().withIndex()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val parts = line.split(Regex("\\s+"))
        val pattern = parts.first()
        val owners = mutableListOf<String>()
        for (o in parts.drop(1)) {
          when {
            o.startsWith("@") && '/' in o ->
              warnings += "$file:${i + 1}: team owner $o isn't supported yet; it owns nothing"
            o.startsWith("@") -> owners += o.drop(1).lowercase()
            else -> warnings += "$file:${i + 1}: owner '$o' isn't a @user; it owns nothing"
          }
        }
        rules += Rule(i + 1, pattern, regexOf(pattern), owners)
      }
      return CodeOwners(rules, warnings)
    }

    /**
     * A gitignore-style pattern as a regex over directory paths without leading or trailing
     * slashes.
     */
    internal fun regexOf(pattern: String): Regex {
      var p = pattern.removeSuffix("/")
      val anchored = p.startsWith("/") || p.removePrefix("/").contains('/')
      p = p.removePrefix("/")
      if (!anchored) p = "**/$p"
      val out = StringBuilder()
      var i = 0
      while (i < p.length) {
        when {
          p.startsWith("**/", i) -> {
            out.append("(?:.*/)?")
            i += 3
          }
          p.startsWith("/**", i) && i + 3 == p.length -> {
            out.append("/.*")
            i += 3
          }
          p.startsWith("**", i) -> {
            out.append(".*")
            i += 2
          }
          p[i] == '*' -> {
            out.append("[^/]*")
            i++
          }
          p[i] == '?' -> {
            out.append("[^/]")
            i++
          }
          else -> {
            out.append(Regex.escape(p[i].toString()))
            i++
          }
        }
      }
      return Regex(out.toString())
    }
  }
}

/** What a CODEOWNERS sync changed, for the startup log. */
data class SyncReport(val changed: List<Account>, val warnings: List<String>)

/**
 * Replaces every account's CODEOWNERS scope: an account whose GitHub handle owns packages gets
 * them, players included, so promoting a player scopes them at once; instructors and admins are
 * never scoped. A sync never promotes or demotes: roles never change. Hand scope (`@role`) loses
 * any package CODEOWNERS now covers. Blocks on the store.
 */
fun syncScopes(store: AccountStore, owners: CodeOwners, packages: Collection<String>): SyncReport {
  val scopes = owners.scopes(packages)
  val changed = mutableListOf<Account>()
  val handles = HashSet<String>()
  for (a in store.all()) {
    val handle = a.githubHandle?.lowercase()
    handle?.let(handles::add)
    val next =
      if (a.role.atLeast(Role.INSTRUCTOR)) {
        a.copy(scope = emptyList(), manualScope = emptyList())
      } else {
        a.copy(
          scope = handle?.let { scopes[it] }?.toList() ?: emptyList(),
          manualScope = a.manualScope.filterNot(owners::covers),
        )
      }
    if (next != a) {
      store.update(next)
      changed += next
    }
  }
  val unknown = scopes.keys.filter { it !in handles }.sorted()
  return SyncReport(
    changed,
    owners.warnings + unknown.map { "CODEOWNERS owner @$it matches no account's GitHub handle" },
  )
}
