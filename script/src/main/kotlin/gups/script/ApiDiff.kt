package gups.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The API-change check: compares the committed baseline `api-<N>.json` with the current model.
 * Without an `api_version` bump, no symbol may be removed or changed, and every added symbol must
 * carry `since` equal to the current `api_version`.
 */
object ApiDiff {
  private val ENGINE_VERSION = Regex("""("engine_version": )"[^"]*"""")

  /** [apiJson] with its `engine_version` value replaced by `"<engine>"`, as baselines store it. */
  fun normalize(apiJson: String): String = ENGINE_VERSION.replaceFirst(apiJson, "$1\"<engine>\"")

  fun check(baselineJson: String, current: ScriptApiModel): List<String> {
    val base = Json.parseToJsonElement(baselineJson)
    val baseVersion = base.jsonObject.getValue("api_version").jsonPrimitive.int
    val n = current.apiVersion
    // A bumped api_version may change anything.
    if (n != baseVersion) return emptyList()

    val before = symbolFields(base)
    val after = symbolFields(Json.parseToJsonElement(current.toJson()))
    val bump = "breaking changes require bumping EngineInfo.API_VERSION"
    val problems = mutableListOf<String>()
    fun beneath(parent: String, s: String) = s.startsWith("$parent.")
    // A removed or added parent stands for its children: report it once.
    fun topmost(list: List<String>) =
      list.filter { s -> list.none { p -> p != s && beneath(p, s) } }

    for (s in topmost((before.keys - after.keys).sorted())) {
      problems += "$s was removed since api-$baseVersion.json; $bump"
    }
    for (s in
      before.keys.filter { it in after && before[it]!!.fields != after[it]!!.fields }.sorted()) {
      problems += "$s changed since api-$baseVersion.json; $bump"
    }
    for (s in topmost((after.keys - before.keys).sorted())) {
      val since = after.getValue(s).since
      if (since != n)
        problems +=
          "$s was added since api-$baseVersion.json with since $since; mark it @ApiSince($n)"
    }
    return problems
  }

  /** A symbol's compared fields, and its `since`. */
  private data class Symbol(val fields: Map<String, JsonElement>, val since: Int?)

  /**
   * Every object with a `symbol`, mapped to its scalar fields and string lists, minus `symbol`,
   * `doc` and `since`.
   */
  private fun symbolFields(root: JsonElement): Map<String, Symbol> {
    val out = HashMap<String, Symbol>()
    fun walk(e: JsonElement) {
      when (e) {
        is JsonArray -> e.forEach(::walk)
        is JsonObject -> {
          (e["symbol"] as? JsonPrimitive)?.let { sym ->
            out[sym.content] =
              Symbol(
                e.filter { (k, v) ->
                  k !in setOf("symbol", "doc", "since") &&
                    (v is JsonPrimitive ||
                      (v is JsonArray && v.isNotEmpty() && v.all { it is JsonPrimitive }))
                },
                (e["since"] as? JsonPrimitive)?.int,
              )
          }
          e.values.forEach(::walk)
        }
        else -> Unit
      }
    }
    walk(root)
    return out
  }
}
