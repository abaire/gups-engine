package gups.cli

import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.NodeRenderer
import org.commonmark.renderer.html.AttributeProvider
import org.commonmark.renderer.html.HtmlNodeRendererContext
import org.commonmark.renderer.html.HtmlRenderer

private val MARKDOWN_PARSER: Parser = Parser.builder().build()

private val MARKDOWN_RENDERER: HtmlRenderer =
  HtmlRenderer.builder()
    .escapeHtml(true)
    .attributeProviderFactory { HeadingIds() }
    .nodeRendererFactory { LinksAsText(it) }
    .build()

/**
 * Renders the changelog and the plugin guide as CommonMark. Raw HTML stays escaped, links and
 * images render as their text so nothing points outside the reference, and headings get ids.
 */
internal fun markdownToHtml(md: String): String =
  MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(md))

/** Heading ids: `api_version-<N>` for a version heading, otherwise a slug; repeats get `-2`, … */
private class HeadingIds : AttributeProvider {
  // One provider per render, so repeats are counted per document.
  private val ids = HashMap<String, Int>()

  override fun setAttributes(node: Node, tagName: String, attributes: MutableMap<String, String>) {
    if (node !is Heading) return
    val base = headingId(plainText(node).trim())
    val n = (ids[base] ?: 0) + 1
    ids[base] = n
    attributes["id"] = if (n == 1) base else "$base-$n"
  }

  private fun headingId(text: String): String {
    API_VERSION_HEADING.matchEntire(text)?.let {
      return "api_version-${it.groupValues[1]}"
    }
    return text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "section" }
  }

  private fun plainText(node: Node): String {
    val sb = StringBuilder()
    var c = node.firstChild
    while (c != null) {
      when (c) {
        is Text -> sb.append(c.literal)
        is Code -> sb.append(c.literal)
        else -> sb.append(plainText(c))
      }
      c = c.next
    }
    return sb.toString()
  }

  companion object {
    private val API_VERSION_HEADING = Regex("""api_version (\d+)( \(unreleased\))?""")
  }
}

/** Renders a link or image as its content alone, keeping the reference offline. */
private class LinksAsText(private val context: HtmlNodeRendererContext) : NodeRenderer {
  override fun getNodeTypes(): Set<Class<out Node>> = setOf(Link::class.java, Image::class.java)

  override fun render(node: Node) {
    var c = node.firstChild
    while (c != null) {
      val next = c.next
      context.render(c)
      c = next
    }
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
