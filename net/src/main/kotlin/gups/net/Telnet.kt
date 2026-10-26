package gups.net

import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Telnet command and option codes (RFC 854 and MUD extensions). */
object Telnet {
  const val IAC = 255
  const val DONT = 254
  const val DO = 253
  const val WONT = 252
  const val WILL = 251
  const val SB = 250
  const val GA = 249
  const val SE = 240
  const val EOR = 239

  const val OPT_ECHO = 1
  const val OPT_TTYPE = 24
  const val OPT_EOR = 25
  const val OPT_NAWS = 31
  const val OPT_CHARSET = 42
  const val OPT_GMCP = 201

  const val TTYPE_IS = 0
  const val TTYPE_SEND = 1
  const val CHARSET_REQUEST = 1
  const val CHARSET_ACCEPTED = 2
  const val CHARSET_REJECTED = 3
}

/** Input lines longer than this many bytes are truncated. */
const val MAX_LINE_BYTES = 512

/** A connection is dropped after this many malformed sequences. */
const val MAX_PROTOCOL_ERRORS = 5

private const val MAX_SUBNEGOTIATION = 8192

/**
 * One telnet connection's protocol state: line framing and option negotiation for NAWS, TTYPE/MTTS,
 * EOR (falling back to GA), GMCP and CHARSET (UTF-8 only). Pure: bytes to send go to [write], so it
 * can be tested against recorded client transcripts.
 *
 * [receive] runs on the connection's reader; the encoders run on the world thread and only read
 * state, which is volatile.
 */
class TelnetConnection(private val write: (ByteArray) -> Unit) {
  @Volatile
  var colorDepth: ColorDepth = ColorDepth.NONE
    private set

  @Volatile
  var eor: Boolean = false
    private set

  /** True once the client has sent any telnet command; plain `nc` never does. */
  @Volatile
  var speaksTelnet: Boolean = false
    private set

  @Volatile
  var gmcp: Boolean = false
    private set

  /** GMCP modules the client listed with `Core.Supports.*`, such as `Char` or `Char.Vitals`. */
  @Volatile
  var gmcpSupports: Set<String> = emptySet()
    private set

  /** The client's `Core.Hello`: its name and version. */
  @Volatile
  var gmcpClient: String? = null
    private set

  var width: Int? = null
    private set

  var height: Int? = null
    private set

  var clientName: String? = null
    private set

  var terminalType: String? = null
    private set

  var mtts: Int? = null
    private set

  var utf8Accepted: Boolean = false
    private set

  /** True while we have asked the client not to echo, around a password prompt. */
  @Volatile private var echoHidden = false

  /** Malformed sequences seen so far. */
  var errors: Int = 0
    private set

  private enum class State {
    DATA,
    IAC,
    NEG,
    SB_OPT,
    SB_DATA,
    SB_IAC,
  }

  private var state = State.DATA
  private var verb = 0
  private var sbOption = 0
  private val sb = ByteArrayOutputStream()
  private val line = ByteArrayOutputStream()
  private var truncated = false
  private var afterCr = false

  /** Options the client has agreed to do (`WILL`), and that we agreed to do (`DO`). */
  private val himOn = HashSet<Int>()
  private val usOn = HashSet<Int>()
  private val asked = HashSet<Int>()
  private val offered = HashSet<Int>()
  private val ttypes = mutableListOf<String>()

  /** Opens negotiation. */
  fun start() {
    for (opt in listOf(Telnet.OPT_TTYPE, Telnet.OPT_NAWS)) {
      asked += opt
      send(Telnet.IAC, Telnet.DO, opt)
    }
    for (opt in listOf(Telnet.OPT_EOR, Telnet.OPT_GMCP, Telnet.OPT_CHARSET)) {
      offered += opt
      send(Telnet.IAC, Telnet.WILL, opt)
    }
  }

  /** Feeds received bytes; [onLine] gets each complete line and whether it was truncated. */
  fun receive(bytes: ByteArray, onLine: (String, Boolean) -> Unit) {
    for (raw in bytes) {
      val b = raw.toInt() and 0xff
      when (state) {
        State.DATA -> if (b == Telnet.IAC) state = State.IAC else data(b, onLine)
        State.IAC -> {
          speaksTelnet = true
          state = State.DATA
          when (b) {
            Telnet.IAC -> data(b, onLine)
            Telnet.WILL,
            Telnet.WONT,
            Telnet.DO,
            Telnet.DONT -> {
              verb = b
              state = State.NEG
            }
            Telnet.SB -> state = State.SB_OPT
            Telnet.SE -> errors++
            in Telnet.EOR..Telnet.GA -> Unit
            else -> errors++
          }
        }
        State.NEG -> {
          state = State.DATA
          negotiate(verb, b)
        }
        State.SB_OPT -> {
          sbOption = b
          sb.reset()
          state = State.SB_DATA
        }
        State.SB_DATA ->
          when {
            b == Telnet.IAC -> state = State.SB_IAC
            sb.size() >= MAX_SUBNEGOTIATION -> {
              errors++
              state = State.DATA
            }
            else -> sb.write(b)
          }
        State.SB_IAC ->
          when (b) {
            Telnet.SE -> {
              state = State.DATA
              subnegotiation(sbOption, sb.toByteArray())
            }
            Telnet.IAC -> {
              sb.write(b)
              state = State.SB_DATA
            }
            else -> {
              errors++
              state = State.DATA
            }
          }
      }
    }
  }

  private fun data(b: Int, onLine: (String, Boolean) -> Unit) {
    if (afterCr) {
      afterCr = false
      if (b == '\n'.code || b == 0) return
    }
    when (b) {
      '\r'.code,
      '\n'.code -> {
        afterCr = b == '\r'.code
        val text = String(line.toByteArray(), Charsets.UTF_8).filter { it >= ' ' || it == '\t' }
        onLine(text, truncated)
        line.reset()
        truncated = false
      }
      8,
      127 -> {
        val bytes = line.toByteArray()
        line.reset()
        if (bytes.isNotEmpty()) line.write(bytes, 0, bytes.size - 1)
      }
      else -> if (line.size() < MAX_LINE_BYTES) line.write(b) else truncated = true
    }
  }

  private fun negotiate(verb: Int, opt: Int) {
    when (verb) {
      Telnet.WILL ->
        if (opt == Telnet.OPT_TTYPE || opt == Telnet.OPT_NAWS) {
          if (himOn.add(opt)) {
            if (opt !in asked) send(Telnet.IAC, Telnet.DO, opt)
            if (opt == Telnet.OPT_TTYPE)
              send(Telnet.IAC, Telnet.SB, opt, Telnet.TTYPE_SEND, Telnet.IAC, Telnet.SE)
          }
        } else if (asked.add(opt)) {
          send(Telnet.IAC, Telnet.DONT, opt)
        }
      Telnet.WONT -> if (himOn.remove(opt)) send(Telnet.IAC, Telnet.DONT, opt)
      // ECHO is ours only while a password prompt hides input; refuse it otherwise.
      Telnet.DO ->
        if (opt == Telnet.OPT_ECHO) {
          if (!echoHidden && offered.add(opt)) send(Telnet.IAC, Telnet.WONT, opt)
        } else if (opt == Telnet.OPT_EOR || opt == Telnet.OPT_GMCP || opt == Telnet.OPT_CHARSET) {
          if (usOn.add(opt)) {
            if (opt !in offered) send(Telnet.IAC, Telnet.WILL, opt)
            when (opt) {
              Telnet.OPT_EOR -> eor = true
              Telnet.OPT_GMCP -> gmcp = true
              Telnet.OPT_CHARSET ->
                send(
                  byteArrayOf(Telnet.IAC, Telnet.SB, opt, Telnet.CHARSET_REQUEST) +
                    ";UTF-8".toByteArray() +
                    byteArrayOf(Telnet.IAC, Telnet.SE)
                )
            }
          }
        } else if (offered.add(opt)) {
          send(Telnet.IAC, Telnet.WONT, opt)
        }
      Telnet.DONT -> {
        if (opt == Telnet.OPT_ECHO) return
        if (usOn.remove(opt)) send(Telnet.IAC, Telnet.WONT, opt)
        if (opt == Telnet.OPT_EOR) eor = false
        if (opt == Telnet.OPT_GMCP) gmcp = false
      }
    }
  }

  private fun subnegotiation(opt: Int, data: ByteArray) {
    when (opt) {
      Telnet.OPT_TTYPE -> {
        if (data.isEmpty() || data[0].toInt() != Telnet.TTYPE_IS) return run { errors++ }
        val name = String(data, 1, data.size - 1, Charsets.US_ASCII)
        val repeat = ttypes.lastOrNull() == name
        if (!repeat) ttypes += name
        when (ttypes.size) {
          1 -> clientName = name
          2 -> terminalType = name
        }
        if (name.startsWith("MTTS ")) mtts = name.removePrefix("MTTS ").trim().toIntOrNull()
        colorDepth = depth()
        // MTTS: ask up to three times; a repeated answer means the list is exhausted.
        if (!repeat && ttypes.size < 3)
          send(Telnet.IAC, Telnet.SB, Telnet.OPT_TTYPE, Telnet.TTYPE_SEND, Telnet.IAC, Telnet.SE)
      }
      Telnet.OPT_NAWS -> {
        if (data.size != 4) return run { errors++ }
        width = (data[0].toInt() and 0xff shl 8) or (data[1].toInt() and 0xff)
        height = (data[2].toInt() and 0xff shl 8) or (data[3].toInt() and 0xff)
      }
      Telnet.OPT_CHARSET ->
        when (data.firstOrNull()?.toInt()) {
          Telnet.CHARSET_ACCEPTED ->
            utf8Accepted =
              String(data, 1, data.size - 1, Charsets.US_ASCII).equals("UTF-8", ignoreCase = true)
          Telnet.CHARSET_REJECTED -> utf8Accepted = false
          Telnet.CHARSET_REQUEST -> {
            val offeredSets = String(data, 1, data.size - 1, Charsets.US_ASCII)
            val ok =
              offeredSets.drop(1).split(offeredSets.firstOrNull() ?: ';').any {
                it.equals("UTF-8", ignoreCase = true)
              }
            utf8Accepted = ok
            send(
              if (ok) {
                byteArrayOf(Telnet.IAC, Telnet.SB, opt, Telnet.CHARSET_ACCEPTED) +
                  "UTF-8".toByteArray() +
                  byteArrayOf(Telnet.IAC, Telnet.SE)
              } else {
                byteArrayOf(
                  Telnet.IAC,
                  Telnet.SB,
                  opt,
                  Telnet.CHARSET_REJECTED,
                  Telnet.IAC,
                  Telnet.SE,
                )
              }
            )
          }
          else -> errors++
        }
      Telnet.OPT_GMCP -> gmcpIn(String(data, Charsets.UTF_8))
      else -> Unit
    }
  }

  /** Color depth from MTTS bits when sent, else from the terminal type names. */
  private fun depth(): ColorDepth {
    mtts?.let { bits ->
      return when {
        bits and 8 != 0 -> ColorDepth.XTERM256
        bits and 1 != 0 -> ColorDepth.ANSI16
        else -> ColorDepth.NONE
      }
    }
    val names = ttypes.joinToString(" ").uppercase()
    return when {
      "256COLOR" in names || "TRUECOLOR" in names || "MUDLET" in names || "TINTIN" in names ->
        ColorDepth.XTERM256
      listOf("ANSI", "XTERM", "VT100", "VT102", "VT220", "LINUX", "SCREEN", "RXVT").any {
        it in names
      } -> ColorDepth.ANSI16
      else -> ColorDepth.NONE
    }
  }

  /** Game text as bytes for this client: markup rendered, CRLF line ends, UTF-8. */
  fun encodeText(text: String): ByteArray =
    Ansi.render(text, colorDepth)
      .replace("\r\n", "\n")
      .replace("\n", "\r\n")
      .toByteArray(Charsets.UTF_8)

  /** A prompt, ended with EOR if the client asked for it, else GA for real telnet clients. */
  fun encodePrompt(text: String): ByteArray {
    val body = encodeText(text)
    return when {
      eor -> body + byteArrayOf(Telnet.IAC, Telnet.EOR)
      speaksTelnet -> body + byteArrayOf(Telnet.IAC, Telnet.GA)
      else -> body
    }
  }

  /**
   * One GMCP message from the client. Only `Core.*` is read; a malformed message counts as a
   * protocol error.
   */
  private fun gmcpIn(message: String) {
    val space = message.indexOf(' ')
    val pkg = if (space < 0) message else message.substring(0, space)
    val body =
      if (space < 0) null
      else
        runCatching { Json.parseToJsonElement(message.substring(space + 1)) }
          .getOrElse {
            return run { errors++ }
          }
    if (pkg.equals("core.hello", ignoreCase = true)) {
      gmcpClient =
        (body as? JsonObject)?.let { o ->
          listOfNotNull(o["client"], o["version"])
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .joinToString(" ")
        }
    }
    gmcpSupports = updateSupports(gmcpSupports, pkg, body)
  }

  /**
   * True if GMCP is on and the client supports [pkg] or a module above it (`Char` covers
   * `Char.Vitals`).
   */
  fun wantsGmcp(pkg: String): Boolean = gmcp && supportsGmcp(gmcpSupports, pkg)

  /** `IAC SB GMCP <pkg> <json> IAC SE`, with any IAC byte in the payload doubled. */
  fun encodeGmcp(pkg: String, data: Any?): ByteArray {
    val body =
      (if (data == null && pkg.startsWith("Core.")) pkg else "$pkg ${toJson(data)}").toByteArray(
        Charsets.UTF_8
      )
    val out = ByteArrayOutputStream(body.size + 6)
    out.write(byteArrayOf(Telnet.IAC, Telnet.SB, Telnet.OPT_GMCP))
    for (b in body) {
      out.write(b.toInt())
      if (b.toInt() and 0xff == Telnet.IAC) out.write(Telnet.IAC)
    }
    out.write(byteArrayOf(Telnet.IAC, Telnet.SE))
    return out.toByteArray()
  }

  /**
   * `IAC WILL ECHO` to hide typed input, `IAC WONT ECHO` to show it again. Clients that refuse
   * simply keep echoing. Plain `nc` gets nothing.
   */
  fun encodeEcho(hidden: Boolean): ByteArray {
    if (!speaksTelnet || hidden == echoHidden) return ByteArray(0)
    echoHidden = hidden
    return byteArrayOf(Telnet.IAC, if (hidden) Telnet.WILL else Telnet.WONT, Telnet.OPT_ECHO)
  }

  private fun send(vararg bytes: Int) = write(byteArrayOf(*bytes))

  private fun send(bytes: ByteArray) = write(bytes)
}

private fun byteArrayOf(vararg ints: Int): ByteArray = ByteArray(ints.size) { ints[it].toByte() }

/** A plain value from `core` as JSON. */
fun toJson(v: Any?): JsonElement =
  when (v) {
    null -> JsonNull
    is String -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to toJson(value) })
    is Iterable<*> -> JsonArray(v.map(::toJson))
    is Array<*> -> JsonArray(v.map(::toJson))
    else -> JsonPrimitive(v.toString())
  }

/**
 * [supports] after the client's `Core.Supports.Set`, `Add` or `Remove` message [pkg]; unchanged for
 * others.
 */
fun updateSupports(supports: Set<String>, pkg: String, body: JsonElement?): Set<String> {
  fun modules(): List<String> =
    (body as? JsonArray)?.mapNotNull {
      (it as? JsonPrimitive)?.contentOrNull?.trim()?.substringBefore(' ')
    } ?: emptyList()
  return when (pkg.lowercase()) {
    "core.supports.set" -> modules().toSet()
    "core.supports.add" -> supports + modules()
    "core.supports.remove" -> supports - modules().toSet()
    else -> supports
  }
}

/** True if [supports] lists [pkg] or a module above it, ignoring case. */
fun supportsGmcp(supports: Set<String>, pkg: String): Boolean =
  supports.any { pkg.equals(it, ignoreCase = true) || pkg.startsWith("$it.", ignoreCase = true) }
