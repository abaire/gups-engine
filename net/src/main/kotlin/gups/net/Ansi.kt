package gups.net

/** How many colors a client can show, taken from MTTS. */
enum class ColorDepth {
  NONE,
  ANSI16,
  XTERM256,
}

/**
 * Color markup in game text, rendered per client:
 * - `{red}` … `{white}` and `{bright_red}` … `{bright_white}` for the 16 ANSI colors,
 * - `{bold}`, `{underline}`, and `{/}` to reset,
 * - `{x0}` … `{x255}` for xterm-256 colors, downgraded to the nearest of the 16 when needed,
 * - `{{` for a literal brace.
 *
 * Unknown tags are left as they are. Clients without color get the text with tags removed.
 */
object Ansi {
  private val NAMES = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")
  private val TAG =
    Regex(
      "\\{\\{|\\{(/|bold|underline|(?:bright_)?(?:black|red|green|yellow|blue|magenta|cyan|white)|x(\\d{1,3}))}"
    )

  /** The 16 base colors as RGB, for downgrading xterm-256 colors. */
  private val BASE_RGB =
    listOf(
      0x000000,
      0x800000,
      0x008000,
      0x808000,
      0x000080,
      0x800080,
      0x008080,
      0xc0c0c0,
      0x808080,
      0xff0000,
      0x00ff00,
      0xffff00,
      0x0000ff,
      0xff00ff,
      0x00ffff,
      0xffffff,
    )

  fun render(text: String, depth: ColorDepth): String {
    var colored = false
    val out =
      TAG.replace(text) { m ->
        val tag = m.groupValues[1]
        if (m.value == "{{") return@replace "{"
        val n = m.groupValues[2].toIntOrNull()
        if (n != null && n > 255) return@replace m.value
        if (depth == ColorDepth.NONE) return@replace ""
        val code =
          when {
            tag == "/" -> "0"
            tag == "bold" -> "1"
            tag == "underline" -> "4"
            n != null -> if (depth == ColorDepth.XTERM256) "38;5;$n" else sgr16(nearest16(n))
            else -> {
              val bright = tag.startsWith("bright_")
              sgr16(NAMES.indexOf(tag.removePrefix("bright_")) + if (bright) 8 else 0)
            }
          }
        colored = tag != "/"
        "\u001b[${code}m"
      }
    return if (colored) out + "\u001b[0m" else out
  }

  /** Removes markup, for logs and headless sinks. */
  fun strip(text: String): String = render(text, ColorDepth.NONE)

  private fun sgr16(i: Int): String = if (i < 8) "${30 + i}" else "${90 + i - 8}"

  private fun rgb(n: Int): Int =
    when {
      n < 16 -> BASE_RGB[n]
      n < 232 -> {
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        val c = n - 16
        (levels[c / 36] shl 16) or (levels[c / 6 % 6] shl 8) or levels[c % 6]
      }
      else -> (8 + (n - 232) * 10).let { (it shl 16) or (it shl 8) or it }
    }

  internal fun nearest16(n: Int): Int {
    if (n < 16) return n
    val c = rgb(n)
    return BASE_RGB.indices.minBy { i ->
      val b = BASE_RGB[i]
      val dr = (c shr 16 and 255) - (b shr 16 and 255)
      val dg = (c shr 8 and 255) - (b shr 8 and 255)
      val db = (c and 255) - (b and 255)
      dr * dr + dg * dg + db * db
    }
  }
}
