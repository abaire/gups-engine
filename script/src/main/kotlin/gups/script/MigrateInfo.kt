package gups.script

import gups.core.EngineInfo
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.starlark.java.syntax.Argument
import net.starlark.java.syntax.CallExpression
import net.starlark.java.syntax.DefStatement
import net.starlark.java.syntax.DictExpression
import net.starlark.java.syntax.DotExpression
import net.starlark.java.syntax.ExpressionStatement
import net.starlark.java.syntax.Identifier
import net.starlark.java.syntax.IntLiteral
import net.starlark.java.syntax.Node
import net.starlark.java.syntax.NodeVisitor
import net.starlark.java.syntax.ParserInput
import net.starlark.java.syntax.StarlarkFile
import net.starlark.java.syntax.StringLiteral

// `gups migrate-info` (SPEC §4.17): where a package uses symbols that changed
// since its api_version. It reads the AST only and never loads the package.

/** One place a package uses a changed symbol. */
data class SymbolUse(
  /** `//zones/forest:mobs.star`. */
  val file: String,
  val line: Int,
  val sourceLine: String,
  val entry: ChangelogEntry,
  val apiVersion: Int,
  val unreleased: Boolean,
)

/** A relevant `behavior` entry the package has no use of, to check by hand (D-175). */
data class GeneralChange(val entry: ChangelogEntry, val apiVersion: Int, val unreleased: Boolean)

/** Thrown for a package that can't be inspected: a syntax error, or no literal `api_version`. */
class MigrateInfoException(message: String) : Exception(message)

data class MigrationReport(
  val pkg: String,
  val pkgApiVersion: Int,
  val currentApiVersion: Int,
  val uses: List<SymbolUse>,
  val generalChanges: List<GeneralChange>,
) {
  fun renderText(): String = buildString {
    append(pkg)
      .append(" (package api_version ")
      .append(pkgApiVersion)
      .append(", current api_version ")
      .append(currentApiVersion)
      .append(")\n")
    if (uses.isEmpty() && generalChanges.isEmpty()) append("  No uses of changed symbols.\n")
    for (u in uses) {
      val e = u.entry
      append("  ")
        .append(u.file)
        .append(':')
        .append(u.line)
        .append(": ")
        .append(u.sourceLine)
        .append('\n')
      append("    [").append(e.kind.wireName).append("] ").append(e.symbol)
      e.replacement?.let { append(" -> ").append(it) }
      append(" (api_version ")
        .append(u.apiVersion)
        .append(if (u.unreleased) ", unreleased)" else ")")
        .append('\n')
      for (l in e.summary.lines()) append("    ").append(l).append('\n')
      for (s in e.snippets) {
        append("    before:\n")
        for (l in s.before.lines()) append("      ").append(l).append('\n')
        append("    after:\n")
        for (l in s.after.lines()) append("      ").append(l).append('\n')
      }
    }
    if (generalChanges.isNotEmpty()) {
      append("  General changes (check by hand):\n")
      for (g in generalChanges) {
        append("    [behavior] ")
          .append(g.entry.symbol)
          .append(" (api_version ")
          .append(g.apiVersion)
          .append(if (g.unreleased) ", unreleased" else "")
          .append("): ")
          .append(g.entry.summary.lines().joinToString(" ") { it.trim() })
          .append('\n')
      }
    }
  }

  fun toJson(): String = prettyJson(toJsonObject())

  internal fun toJsonObject(): JsonObject = buildJsonObject {
    put("package", pkg)
    put("package_api_version", pkgApiVersion)
    put("current_api_version", currentApiVersion)
    put(
      "uses",
      buildJsonArray {
        for (u in uses) {
          add(
            buildJsonObject {
              put("file", u.file)
              put("line", u.line)
              put("source", u.sourceLine)
              put("api_version", u.apiVersion)
              put("unreleased", u.unreleased)
              putEntryFields(u.entry)
            }
          )
        }
      },
    )
    put(
      "general_changes",
      buildJsonArray {
        for (g in generalChanges) {
          add(
            buildJsonObject {
              put("api_version", g.apiVersion)
              put("unreleased", g.unreleased)
              putEntryFields(g.entry)
            }
          )
        }
      },
    )
  }

  companion object {
    /** Several reports as one JSON array. */
    fun toJson(reports: List<MigrationReport>): String =
      prettyJson(buildJsonArray { reports.forEach { add(it.toJsonObject()) } })
  }
}

/** Whether a node uses a symbol; the set holds the receivers that stand for `ctx` in the file. */
private typealias Matcher = (Node, Set<String>) -> Boolean

object MigrateInfo {
  /** Receivers that are never a view (§4.2 item 4). */
  private val NON_VIEW_RECEIVERS = setOf("ctx", "rules_ctx", "t", "w", "p", "diagnostic")
  private val OBJECTS = NON_VIEW_RECEIVERS
  private val CONSTANT = Regex("^[A-Z][A-Z0-9_]*$")

  /** Thrown as [IllegalArgumentException] when [pkg] has no `package.star`. */
  fun inspect(
    dir: WorldDir,
    pkg: String,
    changelog: Changelog,
    model: ScriptApiModel,
    currentApiVersion: Int = EngineInfo.API_VERSION,
  ): MigrationReport {
    val path = pkg.removePrefix("//")
    require(dir.hasPackage(path)) {
      "no package //$path (no $path/${WorldDir.PACKAGE_FILE} under ${dir.root})"
    }
    val label = "//$path"
    val files =
      java.nio.file.Files.list(dir.packageDir(path))
        .use { s ->
          s.filter { it.name.endsWith(".star") && java.nio.file.Files.isRegularFile(it) }.toList()
        }
        .sortedBy { it.name }
    val parsed =
      files.associate { f ->
        val text = f.readText()
        val file =
          StarlarkFile.parse(ParserInput.fromString(text, "$label:${f.name}"), FILE_OPTIONS)
        file.errors().firstOrNull()?.let {
          throw MigrateInfoException(
            "$label:${f.name}:${it.location().line()}: syntax error: ${it.message()}"
          )
        }
        f.name to (file to text.lines())
      }
    val pkgFile = parsed.getValue(WorldDir.PACKAGE_FILE).first
    val pkgCall =
      pkgFile.statements
        .filterIsInstance<ExpressionStatement>()
        .map { it.expression }
        .filterIsInstance<CallExpression>()
        .firstOrNull { (it.function as? Identifier)?.name == "package" }
        ?: throw MigrateInfoException("$label:${WorldDir.PACKAGE_FILE}: no package(...) call")
    fun kw(name: String) = pkgCall.arguments.firstOrNull { it.name == name }?.value
    val pkgApiVersion =
      (kw("api_version") as? IntLiteral)?.value?.toInt()
        ?: throw MigrateInfoException(
          "$label:${WorldDir.PACKAGE_FILE}: package() has no integer literal api_version"
        )
    val isRules = (kw("kind") as? StringLiteral)?.value == "rules"

    val relevant =
      changelog.versions
        .filter { it.apiVersion > pkgApiVersion }
        .flatMap { v -> v.entries.map { GeneralChange(it, v.apiVersion, v.unreleased) } }
    val matchers =
      relevant.mapNotNull { r -> matcher(r.entry.symbol, model, isRules)?.let { r to it } }

    val uses = LinkedHashSet<SymbolUse>()
    for ((name, pair) in parsed) {
      val (file, lines) = pair
      val fileLabel = "$label:$name"
      val ctxNames =
        setOf("ctx") +
          file.statements.filterIsInstance<DefStatement>().mapNotNull {
            it.parameters.firstOrNull()?.name
          }
      file.accept(
        object : NodeVisitor() {
          fun record(node: Node) {
            val line = node.startLocation.line()
            for ((r, m) in matchers) {
              if (m(node, ctxNames)) {
                uses +=
                  SymbolUse(
                    fileLabel,
                    line,
                    lines.getOrElse(line - 1) { "" }.trim(),
                    r.entry,
                    r.apiVersion,
                    r.unreleased,
                  )
              }
            }
          }

          override fun visit(node: CallExpression) {
            record(node)
            super.visit(node)
          }

          override fun visit(node: DotExpression) {
            record(node)
            super.visit(node)
          }

          override fun visit(node: Identifier) {
            record(node)
            super.visit(node)
          }

          override fun visit(node: DefStatement) {
            record(node)
            super.visit(node)
          }

          override fun visit(node: Argument) {
            record(node)
            super.visit(node)
          }
        }
      )
    }
    val sorted = uses.sortedWith(compareBy({ it.file }, { it.line }, { it.entry.symbol }))
    val used = sorted.map { it.entry }.toSet()
    val general = relevant.filter { it.entry.kind == ChangeKind.BEHAVIOR && it.entry !in used }
    return MigrationReport(label, pkgApiVersion, currentApiVersion, sorted, general)
  }

  /**
   * The matcher for [symbol] per the table of §4.2 item 4, or null for a shape it doesn't cover
   * (D-175).
   */
  private fun matcher(symbol: String, model: ScriptApiModel, isRules: Boolean): Matcher? {
    fun calleeName(c: CallExpression) = (c.function as? Identifier)?.name
    fun receiver(d: DotExpression) = (d.`object` as? Identifier)?.name
    when {
      symbol.startsWith("hook:") -> {
        val name = symbol.removePrefix("hook:")
        return { n, _ -> n is Argument && n.name == name }
      }
      symbol.startsWith("rules:") -> {
        val name = symbol.removePrefix("rules:")
        return { n, _ -> isRules && n is DefStatement && n.identifier.name == name }
      }
      symbol.startsWith("modifier:") -> {
        val point = symbol.removePrefix("modifier:")
        return { n, _ ->
          n is Argument &&
            n.name == "modifiers" &&
            (n.value as? DictExpression)?.entries?.any {
              (it.key as? StringLiteral)?.value == point
            } == true
        }
      }
      symbol.startsWith("view:") -> {
        val parts = symbol.removePrefix("view:").split('.')
        return when (parts.size) {
          2 -> { n, _ ->
              n is DotExpression && n.field.name == parts[1] && receiver(n) !in NON_VIEW_RECEIVERS
            }
          3 -> methodParam(parts[1], parts[2])
          else -> null
        }
      }
    }
    val parts = symbol.split('.')
    if (parts[0] in OBJECTS) {
      val obj = parts[0]
      return when (parts.size) {
        2 -> { n, ctxNames ->
            n is DotExpression &&
              n.field.name == parts[1] &&
              (receiver(n) == obj || (obj == "ctx" && receiver(n) in ctxNames))
          }
        3 -> methodParam(parts[1], parts[2])
        else -> null
      }
    }
    return when {
      parts.size == 1 && CONSTANT.matches(symbol) -> { n, _ -> n is Identifier && n.name == symbol }
      parts.size == 1 -> { n, _ -> n is CallExpression && calleeName(n) == symbol }
      parts.size == 2 -> {
        val index = positionalIndex(model, parts[0], parts[1])
        return { n, _ ->
          n is CallExpression &&
            calleeName(n) == parts[0] &&
            (n.arguments.any { it.name == parts[1] } ||
              (index != null && n.arguments.filterIsInstance<Argument.Positional>().size > index))
        }
      }
      else -> null
    }
  }

  /** A call `<x>.method(...)` with keyword [param]. */
  private fun methodParam(method: String, param: String): Matcher = { n, _ ->
    n is CallExpression &&
      (n.function as? DotExpression)?.field?.name == method &&
      n.arguments.any { it.name == param }
  }

  /** The index of [param] among [fn]'s positional parameters in [model], if it has one. */
  private fun positionalIndex(model: ScriptApiModel, fn: String, param: String): Int? {
    (model.declarations + model.settings)
      .firstOrNull { it.name == fn }
      ?.let { d ->
        return d.params
          .filter { it.positional }
          .indexOfFirst { it.name == param }
          .takeIf { it >= 0 }
      }
    (model.effects + model.behaviors)
      .firstOrNull { it.name == fn }
      ?.let { m ->
        return m.params
          .filter { it.positional && !it.varargs }
          .indexOfFirst { it.name == param }
          .takeIf { it >= 0 }
      }
    return null
  }
}
