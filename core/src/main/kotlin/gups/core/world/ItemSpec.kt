package gups.core.world

import gups.core.model.Item

private val WHITESPACE = Regex("\\s+")

/** [s] split at its first whitespace: the first word and the trimmed rest. */
internal fun splitFirst(s: String): Pair<String, String> {
  val t = s.trim()
  val i = t.indexOfFirst { it.isWhitespace() }
  return if (i < 0) t to "" else t.substring(0, i) to t.substring(i + 1).trim()
}

/**
 * This string split into what and where around the last match of [separator], or around its last
 * word when there is no separator. Null if there is no separator and fewer than two words.
 */
internal fun String.splitAtLast(separator: Regex): Pair<String, String>? {
  val match = separator.findAll(this).lastOrNull()
  if (match != null) {
    return substring(0, match.range.first).trim() to substring(match.range.last + 1).trim()
  }
  val words = trim().split(WHITESPACE).filter { it.isNotEmpty() }
  if (words.size < 2) return null
  return words.dropLast(1).joinToString(" ") to words.last()
}

/** Splits `2.sword` into its ordinal and keyword; a keyword with no ordinal is the first. */
internal fun String.ordinal(): Pair<Int, String> {
  val t = trim().lowercase()
  val dot = t.indexOf('.')
  if (dot > 0)
    t.substring(0, dot).toIntOrNull()?.let {
      return it to t.substring(dot + 1)
    }
  return 1 to t
}

/**
 * A parsed `<what>`: [all] for `all` and `all <keyword>`, [count] for an explicit unit count,
 * [ordinal] for `2.sword` (default 1), and [keyword] (null only for bare `all`).
 */
internal data class ItemSpec(
  val all: Boolean,
  val count: Int?,
  val ordinal: Int,
  val keyword: String?,
)

/** True if bare `all` may pick [this] up: not tagged `no_all` and not a keyring-only key. */
internal val Item.inBareAll: Boolean
  get() = "no_all" !in tags && !onKeyring

/** Parses `<what>` into an [ItemSpec], or null when it is not a valid item specifier. */
internal fun parseItemSpec(raw: String): ItemSpec? {
  val t = raw.trim()
  if (t.isEmpty()) return null
  if (t.equals("all", ignoreCase = true))
    return ItemSpec(all = true, count = null, ordinal = 1, keyword = null)
  val (first, rest) = splitFirst(t)
  if (first.equals("all", ignoreCase = true)) return ItemSpec(true, null, 1, rest.lowercase())
  // A count too large for an Int is capped like any other count larger than what is there.
  val count = first.toIntOrNull() ?: Int.MAX_VALUE.takeIf { first.all(Char::isDigit) }
  if (count == null) return t.toKeywordSpec(count = null)
  return if (count < 1 || rest.isEmpty()) null else rest.toKeywordSpec(count)
}

private fun String.toKeywordSpec(count: Int?): ItemSpec? {
  val (ord, word) = ordinal()
  if (ord < 1 || word.isEmpty()) return null
  return ItemSpec(false, count, ord, word)
}

/**
 * The source stacks [spec] names in [pool] and the units wanted from each: every matching stack for
 * `all`, the [ordinal]th for a bare keyword, or enough stacks to fill [count]. [exclude] still
 * counts toward the ordinal, so `put 2.chest in chest` names the second chest, not the third.
 */
internal fun matchItems(
  pool: List<Item>,
  spec: ItemSpec,
  exclude: Item? = null,
): List<Pair<Item, Int>> {
  val hits =
    pool
      .filter { if (spec.keyword == null) it.inBareAll else it.matches(spec.keyword) }
      .drop(spec.ordinal - 1)
      .filter { it !== exclude }
  if (spec.all) return hits.map { it to it.count }
  if (spec.count == null) return hits.take(1).map { it to it.count }
  return fillCount(hits, spec.count)
}

/** The stacks of [hits], in order, that together supply [wanted] units. */
private fun fillCount(hits: List<Item>, wanted: Int): List<Pair<Item, Int>> {
  var remaining = wanted
  val out = mutableListOf<Pair<Item, Int>>()
  for (item in hits) {
    if (remaining <= 0) break
    val take = minOf(remaining, item.count)
    out += item to take
    remaining -= take
  }
  return out
}
