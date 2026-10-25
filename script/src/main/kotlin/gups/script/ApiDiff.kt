package gups.script

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The API-change check (SPEC_MILESTONE7.md §4.3): every difference between the released
 * baseline `api-<N>.json` and the current model must be covered by a changelog entry, and the
 * released changelog sections must match the frozen `changes-<N>.json`.
 */
object ApiDiff {
    private val ENGINE_VERSION = Regex("""("engine_version": )"[^"]*"""")

    /** [apiJson] with its `engine_version` value replaced by `"<engine>"`, as baselines store it. */
    fun normalize(apiJson: String): String = ENGINE_VERSION.replaceFirst(apiJson, "$1\"<engine>\"")

    fun check(baselineJson: String, frozenJson: String, current: ScriptApiModel, changelog: Changelog): List<String> {
        val problems = mutableListOf<String>()
        val n = current.apiVersion
        val next = "`## api_version ${n + 1} (unreleased)`"

        // 1. Released sections are frozen; only `added` entries may follow in section N.
        val active = mutableListOf<ChangelogEntry>()
        val frozen = Json.parseToJsonElement(frozenJson).jsonObject.getValue("versions").jsonArray.map { it.jsonObject }
        val frozenVersions = frozen.map { it.getValue("api_version").jsonPrimitive.int }.toSet()
        for (fv in frozen) {
            val version = fv.getValue("api_version").jsonPrimitive.int
            val section = changelog.versions.firstOrNull { it.apiVersion == version && !it.unreleased }
            if (section == null) {
                problems += "released section `## api_version $version` is missing from the changelog"
                continue
            }
            val released = fv.getValue("entries").jsonArray.map { it.jsonObject }
            for ((i, old) in released.withIndex()) {
                if (section.entries.getOrNull(i)?.toJsonObject() != old) {
                    problems += "released entry '${old.str("title")}' of api_version $version was edited or removed; restore it as it is in changes-$n.json"
                }
            }
            for (e in section.entries.drop(released.size)) {
                if (version == n && e.kind == ChangeKind.ADDED) {
                    active += e
                } else {
                    problems += "entry '${e.title}' (${e.kind.wireName} ${e.symbol}) was added to released api_version $version; " +
                        "breaking changes since api-$n.json go under $next"
                }
            }
        }
        for (v in changelog.versions) {
            if (v.unreleased) {
                // 2. The unreleased section is N + 1.
                if (v.apiVersion != n + 1) problems += "the unreleased section is api_version ${v.apiVersion}; it must be ${n + 1}"
                active += v.entries
            } else if (v.apiVersion !in frozenVersions) {
                problems += "released section `## api_version ${v.apiVersion}` is not in changes-$n.json; " +
                    "mark it (unreleased) or run `gups docs --update-baseline`"
            }
        }

        // 4. Diff the symbols' kept fields.
        val before = symbolFields(Json.parseToJsonElement(baselineJson))
        val after = symbolFields(Json.parseToJsonElement(current.toJson()))
        val removed = (before.keys - after.keys).sorted()
        val added = (after.keys - before.keys).sorted()
        val changed = before.keys.filter { it in after && before[it] != after[it] }.sorted()

        // 5. Coverage.
        val used = HashSet<ChangelogEntry>()
        fun covers(symbol: String, s: String) = s == symbol || s.startsWith("$symbol.")
        fun beneath(symbol: String, s: String) = s.startsWith("$symbol.")
        fun cover(s: String, ok: (ChangelogEntry) -> Boolean): Boolean {
            val by = active.filter(ok)
            used += by
            return by.isNotEmpty()
        }
        val uncoveredRemoved = removed.filterNot { s ->
            cover(s) { e ->
                (e.kind in setOf(ChangeKind.REMOVED, ChangeKind.RENAMED) && covers(e.symbol, s)) ||
                    (e.kind == ChangeKind.SIGNATURE && beneath(e.symbol, s))
            }
        }
        val uncoveredAdded = added.filterNot { s ->
            cover(s) { e ->
                (e.kind == ChangeKind.ADDED && covers(e.symbol, s)) ||
                    (e.kind == ChangeKind.RENAMED && e.replacement != null && covers(e.replacement, s)) ||
                    (e.kind == ChangeKind.SIGNATURE && beneath(e.symbol, s))
            }
        }
        val uncoveredChanged = changed.filterNot { s ->
            cover(s) { e -> e.kind in setOf(ChangeKind.SIGNATURE, ChangeKind.BEHAVIOR) && covers(e.symbol, s) }
        }
        // A removed or added parent stands for its children: report it once (D-174).
        fun topmost(list: List<String>) = list.filter { s -> list.none { p -> p != s && beneath(p, s) } }
        for (s in topmost(uncoveredRemoved)) {
            problems += "$s was removed since api-$n.json; add a `removed` or `renamed` entry for it under $next"
        }
        for (s in topmost(uncoveredAdded)) {
            problems += "$s was added since api-$n.json; add a `kind: added` entry for it under $next or `## api_version $n`"
        }
        for (s in uncoveredChanged) {
            problems += "$s changed since api-$n.json; add a `signature` or `behavior` entry for it under $next"
        }

        // 6. No stale entries.
        for (e in active) {
            if (e.kind == ChangeKind.RENAMED && (e.replacement ?: "") !in after) {
                problems += "entry '${e.title}' renames ${e.symbol} to ${e.replacement}, which is not a current symbol"
            }
            if (e.kind == ChangeKind.BEHAVIOR) {
                if (e.symbol !in after) problems += "entry '${e.title}' describes a behavior change of ${e.symbol}, which is not a current symbol"
            } else if (e !in used) {
                problems += "entry '${e.title}' (${e.kind.wireName} ${e.symbol}) matches no API difference since api-$n.json; remove it or fix its symbol"
            }
        }
        return problems
    }

    /** Every object with a `symbol`, mapped to its scalar fields and string lists, minus `symbol`, `doc` and `since`. */
    private fun symbolFields(root: JsonElement): Map<String, Map<String, JsonElement>> {
        val out = HashMap<String, Map<String, JsonElement>>()
        fun walk(e: JsonElement) {
            when (e) {
                is JsonArray -> e.forEach(::walk)
                is JsonObject -> {
                    (e["symbol"] as? JsonPrimitive)?.let { sym ->
                        out[sym.content] = e.filter { (k, v) ->
                            k !in setOf("symbol", "doc", "since") &&
                                (v is JsonPrimitive || (v is JsonArray && v.isNotEmpty() && v.all { it is JsonPrimitive }))
                        }
                    }
                    e.values.forEach(::walk)
                }
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
}
