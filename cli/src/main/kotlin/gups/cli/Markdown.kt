package gups.cli

/**
 * The small Markdown subset the API reference renders: the changelog and, later, the plugin
 * guide (SPEC_MILESTONE7.md §3.1). All text is escaped, so no raw HTML passes through, and
 * links render as their text so nothing points outside the reference.
 */
object Markdown {
    private val API_VERSION_HEADING = Regex("""api_version (\d+)( \(unreleased\))?""")
    private val HEADING = Regex("""(#{1,4}) +(.*)""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")

    fun toHtml(md: String): String {
        val out = StringBuilder()
        val ids = HashMap<String, Int>()
        val lines = md.replace("\r\n", "\n").split('\n')
        var i = 0
        val paragraph = mutableListOf<String>()
        val list = mutableListOf<String>()

        fun flush() {
            if (paragraph.isNotEmpty()) {
                out.append("<p>").append(paragraph.joinToString("\n") { inline(it) }).append("</p>\n")
                paragraph.clear()
            }
            if (list.isNotEmpty()) {
                out.append("<ul>\n")
                for (item in list) out.append("<li>").append(inline(item)).append("</li>\n")
                out.append("</ul>\n")
                list.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            val heading = HEADING.matchEntire(trimmed)
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val code = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) code += lines[i++]
                    out.append("<pre><code>").append(code.joinToString("\n") { esc(it) }).append("</code></pre>\n")
                }
                heading != null -> {
                    flush()
                    val level = heading.groupValues[1].length
                    val text = heading.groupValues[2].trim()
                    val id = uniqueId(headingId(text), ids)
                    out.append("<h$level id=\"").append(esc(id)).append("\">").append(inline(text)).append("</h$level>\n")
                }
                trimmed.isEmpty() -> flush()
                trimmed.startsWith("- ") -> {
                    if (paragraph.isNotEmpty()) flush()
                    list += trimmed.removePrefix("- ").trim()
                }
                list.isNotEmpty() && line.startsWith(" ") -> list[list.lastIndex] = list.last() + " " + trimmed
                else -> {
                    if (list.isNotEmpty()) flush()
                    paragraph += trimmed
                }
            }
            i++
        }
        flush()
        return out.toString()
    }

    /** `api_version-<N>` for a version heading, otherwise a slug of [text]. */
    private fun headingId(text: String): String {
        API_VERSION_HEADING.matchEntire(text)?.let { return "api_version-${it.groupValues[1]}" }
        return text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "section" }
    }

    private fun uniqueId(base: String, ids: MutableMap<String, Int>): String {
        val n = (ids[base] ?: 0) + 1
        ids[base] = n
        return if (n == 1) base else "$base-$n"
    }

    /** Inline `code`, **bold** and [links](…) as text; everything escaped. */
    private fun inline(text: String): String {
        val sb = StringBuilder()
        val parts = text.split('`')
        // An unmatched trailing backtick stays literal.
        val closed = if (parts.size % 2 == 0) parts.size - 1 else parts.size
        for ((n, part) in parts.withIndex()) {
            when {
                n >= closed -> sb.append(esc("`" + part))
                n % 2 == 1 -> sb.append("<code>").append(esc(part)).append("</code>")
                else -> sb.append(BOLD.replace(esc(LINK.replace(part) { it.groupValues[1] })) { "<strong>${it.groupValues[1]}</strong>" })
            }
        }
        return sb.toString()
    }
}

/** HTML-escapes `&`, `<`, `>` and `"`. */
internal fun esc(text: String): String {
    val sb = StringBuilder(text.length)
    for (c in text) {
        when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}
