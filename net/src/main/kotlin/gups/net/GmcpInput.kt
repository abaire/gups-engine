package gups.net

import gups.core.ClientGmcpEvent
import gups.core.SessionId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Largest inbound GMCP body, in UTF-8 bytes, that is passed to the world. */
const val MAX_GMCP_IN_BYTES = 4096

/** Deepest nesting of lists and objects in an inbound GMCP body. */
const val MAX_GMCP_IN_DEPTH = 8

/** An inbound GMCP body as plain values, or [Malformed]. */
sealed interface GmcpBody {
  data class Ok(val data: Any?) : GmcpBody

  data object Malformed : GmcpBody
}

/** True for the `Core.*` packages `net` reads itself; every other package is GMCP input. */
fun isCoreGmcp(pkg: String): Boolean = pkg.startsWith("core.", ignoreCase = true)

/** The world event for a GMCP message [pkg] with [body] from session [id], received now. */
internal fun gmcpEvent(id: SessionId, pkg: String, body: GmcpBody) =
  ClientGmcpEvent(
    id,
    pkg,
    (body as? GmcpBody.Ok)?.data,
    body is GmcpBody.Malformed,
    System.nanoTime(),
  )

private class GmcpTooDeep : RuntimeException()

/**
 * Parses [text], the part after the package name (null when the message has none), into plain
 * values: String, Long, Double, Boolean, List, Map<String, Any?> or null.
 */
fun decodeGmcpBody(text: String?): GmcpBody {
  if (text == null) return GmcpBody.Ok(null)
  if (text.toByteArray(Charsets.UTF_8).size > MAX_GMCP_IN_BYTES) return GmcpBody.Malformed
  val element =
    runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return GmcpBody.Malformed
  return decodeTree(element)
}

/** [element], already parsed (null when the message has no body), held to the same limits. */
fun decodeGmcpElement(element: JsonElement?): GmcpBody {
  if (element == null) return GmcpBody.Ok(null)
  if (element.toString().toByteArray(Charsets.UTF_8).size > MAX_GMCP_IN_BYTES)
    return GmcpBody.Malformed
  return decodeTree(element)
}

private fun decodeTree(element: JsonElement): GmcpBody =
  try {
    GmcpBody.Ok(fromJson(element))
  } catch (_: GmcpTooDeep) {
    GmcpBody.Malformed
  }

/**
 * [e] as plain values; throws [GmcpTooDeep] when lists and objects nest deeper than
 * [MAX_GMCP_IN_DEPTH].
 */
private fun fromJson(e: JsonElement, depth: Int = 0): Any? =
  when (e) {
    is JsonNull -> null
    is JsonPrimitive ->
      when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        e.longOrNull != null -> e.longOrNull
        else -> e.doubleOrNull
      }
    is JsonArray -> {
      if (depth >= MAX_GMCP_IN_DEPTH) throw GmcpTooDeep()
      e.map { fromJson(it, depth + 1) }
    }
    is JsonObject -> {
      if (depth >= MAX_GMCP_IN_DEPTH) throw GmcpTooDeep()
      LinkedHashMap<String, Any?>().also { out ->
        for ((key, value) in e) out[key] = fromJson(value, depth + 1)
      }
    }
  }
