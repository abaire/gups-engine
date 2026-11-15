package gups.core.model

import gups.core.Label

/** Clears the top bit of [h] and maps 0 to 1 so room numbers stay positive 31-bit integers. */
internal fun nonZeroMapNumber(h: Long): Long {
  val masked = h and 0x7FFFFFFFL
  return if (masked == 0L) 1L else masked
}

/** FNV-1a 32-bit over the UTF-8 bytes of [s], with the top bit cleared; never 0. */
fun mapNumberHash(s: String): Long {
  var h = 2166136261L
  for (b in s.toByteArray(Charsets.UTF_8)) {
    h = h xor (b.toLong() and 0xFFL)
    h = (h * 16777619L) and 0xFFFFFFFFL
  }
  return nonZeroMapNumber(h)
}

/**
 * Each label's map number: [hash] of the label, or, when that number is taken by a label sorting
 * earlier, of the label followed by `#1`, `#2`, … until a free one turns up.
 */
fun roomNumbers(
  labels: Collection<Label>,
  hash: (String) -> Long = ::mapNumberHash,
): Map<Label, Long> {
  val sorted = labels.sortedBy { it.v }
  val used = HashSet<Long>(sorted.size)
  val out = LinkedHashMap<Label, Long>(sorted.size)
  for (label in sorted) {
    var candidate = hash(label.v)
    var suffix = 1
    while (!used.add(candidate)) {
      candidate = hash("${label.v}#${suffix++}")
    }
    out[label] = candidate
  }
  return out
}
