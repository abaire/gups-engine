package gups.script

import gups.core.EngineInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The structured changelog, MIGRATING.md and changes.json (SPEC §4.17).

enum class ChangeKind(val wireName: String, val isBreaking: Boolean) {
  REMOVED("removed", true),
  RENAMED("renamed", true),
  SIGNATURE("signature", true),
  BEHAVIOR("behavior", true),
  ADDED("added", false);

  companion object {
    fun byWireName(name: String): ChangeKind? = entries.firstOrNull { it.wireName == name }
  }
}

data class Snippet(val before: String, val after: String)

data class ChangelogEntry(
  val title: String,
  val kind: ChangeKind,
  val symbol: String,
  val replacement: String?,
  val summary: String,
  val snippets: List<Snippet>,
  /** The 1-based line of the entry's `###` heading. */
  val line: Int,
) {
  /** The entry as `changes.json` writes it (no line number). */
  fun toJsonObject(): JsonObject = buildJsonObject { putEntryFields(this@ChangelogEntry) }
}

data class ChangelogVersion(
  val apiVersion: Int,
  val unreleased: Boolean,
  val intro: String,
  val entries: List<ChangelogEntry>,
  val line: Int,
) {
  /** The heading exactly as the changelog writes it. */
  val heading: String
    get() = "## api_version $apiVersion" + if (unreleased) " (unreleased)" else ""
}

class ChangelogParseException(val line: Int, val reason: String) :
  IllegalArgumentException("CHANGELOG.md:$line: $reason")

internal val PRETTY_JSON = Json {
  prettyPrint = true
  prettyPrintIndent = "  "
}

/** The `title` … `snippets` fields of an entry, in `changes.json` order. */
internal fun kotlinx.serialization.json.JsonObjectBuilder.putEntryFields(e: ChangelogEntry) {
  put("title", e.title)
  put("kind", e.kind.wireName)
  put("symbol", e.symbol)
  put("replacement", e.replacement)
  put("summary", e.summary)
  put(
    "snippets",
    buildJsonArray {
      for (s in e.snippets) add(
        buildJsonObject {
          put("before", s.before)
          put("after", s.after)
        }
      )
    },
  )
}

internal fun prettyJson(element: JsonElement): String =
  PRETTY_JSON.encodeToString(JsonElement.serializer(), element) + "\n"

data class Changelog(val versions: List<ChangelogVersion>) {
  fun toMigratingMarkdown(): String {
    val sections = versions.filter { v -> v.entries.any { it.kind.isBreaking } }
    if (sections.isEmpty()) {
      return "# Migrating Between api_versions\n\nNo migrations yet: `api_version = 1` is the first script API.\n"
    }
    val blocks =
      mutableListOf(
        "# Migrating Between api_versions",
        "Breaking script-facing changes per `api_version`, with before/after snippets.",
      )
    for (v in sections) {
      blocks += v.heading
      for (e in v.entries.filter { it.kind.isBreaking }) {
        blocks += buildString {
          append("### ").append(e.title).append('\n')
          append("- **kind:** `").append(e.kind.wireName).append("`\n")
          append("- **symbol:** `").append(e.symbol).append('`')
          e.replacement?.let { append("\n- **replacement:** `").append(it).append('`') }
        }
        blocks += e.summary
        for (s in e.snippets) {
          blocks += "```python before\n${s.before}\n```"
          blocks += "```python after\n${s.after}\n```"
        }
      }
    }
    return blocks.joinToString("\n\n") + "\n"
  }

  fun toJson(currentApiVersion: Int = EngineInfo.API_VERSION): String =
    prettyJson(
      buildJsonObject {
        put("api_version", currentApiVersion)
        put(
          "versions",
          buildJsonArray {
            for (v in versions) {
              add(
                buildJsonObject {
                  put("api_version", v.apiVersion)
                  put("unreleased", v.unreleased)
                  put("intro", v.intro)
                  put("entries", buildJsonArray { v.entries.forEach { add(it.toJsonObject()) } })
                }
              )
            }
          },
        )
      }
    )

  companion object {
    private val VERSION_HEADING = Regex("""^## api_version ([1-9][0-9]*)( \(unreleased\))?$""")
    private val META = Regex("""^- ([A-Za-z_]+): ?(.*)$""")
    internal val SYMBOL =
      Regex(
        """^(hook:|view:|rules:|modifier:)?[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$"""
      )
    private val KEYS = setOf("kind", "symbol", "replacement")

    fun parse(markdown: String): Changelog = Parser(markdown.replace("\r\n", "\n")).parse()
  }

  /** A line-by-line state machine over the grammar of §4.1. */
  private class Parser(text: String) {
    private val lines = text.split('\n').let { if (it.last().isEmpty()) it.dropLast(1) else it }
    private val versions = mutableListOf<ChangelogVersion>()

    // The version section being read.
    private var vNumber = 0
    private var vUnreleased = false
    private var vLine = 0
    private val vIntro = mutableListOf<String>()
    private val vEntries = mutableListOf<ChangelogEntry>()
    private var inVersion = false

    // The entry being read.
    private var eTitle: String? = null
    private var eLine = 0
    private val eMeta = LinkedHashMap<String, Pair<String, Int>>()
    private var eMetaDone = false
    private var eKind: ChangeKind? = null
    private val eSummary = mutableListOf<String>()
    private val eSnippets = mutableListOf<Snippet>()
    private var pendingBefore: Pair<String, Int>? = null

    private fun fail(line: Int, reason: String): Nothing =
      throw ChangelogParseException(line, reason)

    fun parse(): Changelog {
      var i = 0
      while (i < lines.size) {
        val line = lines[i]
        val n = i + 1
        when {
          line.startsWith("## ") || line == "##" -> versionHeading(line, n)
          line.startsWith("### ") || line == "###" -> entryHeading(line, n)
          !inVersion ->
            if (META.matches(line)) fail(n, "'$line' must follow an entry's '### <title>' heading")
          eTitle == null -> {
            if (line.trimStart().startsWith("```"))
              fail(n, "a code block must belong to an entry ('### <title>')")
            vIntro += line
          }
          line.trimStart().startsWith("```") -> i = fence(i)
          !eMetaDone && META.matches(line) -> meta(line, n)
          !eMetaDone && line.isBlank() -> Unit
          else -> {
            if (!eMetaDone) endMeta()
            if (eSnippets.isNotEmpty() || pendingBefore != null) {
              if (line.isNotBlank()) fail(n, "entry '$eTitle' has prose after its first snippet")
            } else {
              eSummary += line
            }
          }
        }
        i++
      }
      endVersion()
      if (versions.isEmpty())
        fail(lines.size.coerceAtLeast(1), "the changelog has no '## api_version N' section")
      return Changelog(versions.toList())
    }

    private fun versionHeading(line: String, n: Int) {
      endVersion()
      val m =
        VERSION_HEADING.matchEntire(line)
          ?: fail(
            n,
            "a version heading must be '## api_version N' or '## api_version N (unreleased)', not '$line'",
          )
      val number =
        m.groupValues[1].toIntOrNull() ?: fail(n, "api_version '${m.groupValues[1]}' is too large")
      val unreleased = m.groupValues[2].isNotEmpty()
      versions.lastOrNull()?.let { prev ->
        if (number >= prev.apiVersion)
          fail(n, "api_version $number must be lower than api_version ${prev.apiVersion} above it")
      }
      if (unreleased && versions.isNotEmpty())
        fail(n, "only the first version section may be (unreleased)")
      inVersion = true
      vNumber = number
      vUnreleased = unreleased
      vLine = n
    }

    private fun entryHeading(line: String, n: Int) {
      if (!inVersion) fail(n, "an entry heading must follow a '## api_version N' heading")
      endEntry()
      val title = line.removePrefix("###").trim()
      if (title.isEmpty()) fail(n, "an entry heading needs a title")
      eTitle = title
      eLine = n
    }

    private fun meta(line: String, n: Int) {
      val m = META.matchEntire(line)!!
      val key = m.groupValues[1]
      val value = m.groupValues[2].trim()
      if (key !in KEYS)
        fail(n, "entry '$eTitle' has unknown key '$key' (expected kind, symbol or replacement)")
      if (key in eMeta) fail(n, "entry '$eTitle' gives '$key' twice")
      when (key) {
        "kind" ->
          eKind =
            ChangeKind.byWireName(value)
              ?: fail(
                n,
                "entry '$eTitle' has kind '$value'; expected ${ChangeKind.entries.joinToString(", ") { it.wireName }}",
              )
        else ->
          if (!SYMBOL.matches(value))
            fail(n, "entry '$eTitle' has $key '$value', which is not a valid symbol")
      }
      eMeta[key] = value to n
    }

    /** Checks the entry's `- key: value` block, once it is over. */
    private fun endMeta() {
      eMetaDone = true
      val title = eTitle!!
      val kind = eKind ?: fail(eLine, "entry '$title' has no '- kind: <kind>'")
      val symbol =
        eMeta["symbol"]?.first ?: fail(eLine, "entry '$title' has no '- symbol: <symbol>'")
      val replacement = eMeta["replacement"]
      when {
        kind == ChangeKind.RENAMED && replacement == null ->
          fail(eLine, "entry '$title' has kind 'renamed' but no '- replacement: <symbol>'")
        kind == ChangeKind.RENAMED && replacement!!.first == symbol ->
          fail(replacement.second, "entry '$title' renames '$symbol' to itself")
        kind == ChangeKind.ADDED && replacement != null ->
          fail(replacement.second, "entry '$title' has kind 'added', which takes no replacement")
      }
    }

    /** Reads the fence opening at [start]; returns the index of its closing line. */
    private fun fence(start: Int): Int {
      val n = start + 1
      if (!eMetaDone) endMeta()
      if (eSummary.joinToString("\n").isBlank())
        fail(n, "entry '$eTitle' needs a summary before its snippets")
      val info = lines[start].trim().removePrefix("```").trim()
      var end = start + 1
      while (end < lines.size && lines[end].trim() != "```") end++
      if (end >= lines.size) fail(n, "the code block opened here is never closed")
      val body = lines.subList(start + 1, end).joinToString("\n")
      when (info) {
        "python before" -> {
          if (pendingBefore != null)
            fail(
              pendingBefore!!.second,
              "a 'python before' block must be followed by a 'python after' block",
            )
          pendingBefore = body to n
        }
        "python after" -> {
          val before =
            pendingBefore ?: fail(n, "a 'python after' block must follow a 'python before' block")
          eSnippets += Snippet(before.first, body)
          pendingBefore = null
        }
        else ->
          fail(n, "a code block in an entry must be 'python before' or 'python after', not '$info'")
      }
      return end
    }

    private fun endEntry() {
      val title = eTitle ?: return
      if (!eMetaDone) endMeta()
      pendingBefore?.let {
        fail(it.second, "a 'python before' block must be followed by a 'python after' block")
      }
      val summary = eSummary.joinToString("\n").trim()
      if (summary.isEmpty()) fail(eLine, "entry '$title' has no summary")
      vEntries +=
        ChangelogEntry(
          title = title,
          kind = eKind!!,
          symbol = eMeta.getValue("symbol").first,
          replacement = eMeta["replacement"]?.first,
          summary = summary,
          snippets = eSnippets.toList(),
          line = eLine,
        )
      eTitle = null
      eMeta.clear()
      eMetaDone = false
      eKind = null
      eSummary.clear()
      eSnippets.clear()
    }

    private fun endVersion() {
      if (!inVersion) return
      endEntry()
      versions +=
        ChangelogVersion(
          vNumber,
          vUnreleased,
          vIntro.joinToString("\n").trim(),
          vEntries.toList(),
          vLine,
        )
      vIntro.clear()
      vEntries.clear()
      inVersion = false
    }
  }
}
