package gups.persist

import gups.core.model.SchemaNames
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// The schema a database was started with. Saved characters and items are keyed by these names, so
// a later start that declares different ones refuses to run on the same data.

@Serializable
internal data class SchemaJson(
  val stats: List<String>,
  val vitals: List<String>,
  val damageTypes: List<String>,
  val channels: List<String>,
)

object SchemaRecord {
  private val json = Json { encodeDefaults = true }

  fun encode(names: SchemaNames): String =
    json.encodeToString(SchemaJson(names.stats, names.vitals, names.damageTypes, names.channels))

  fun decode(text: String): SchemaNames {
    val s = json.decodeFromString<SchemaJson>(text)
    return SchemaNames(s.stats, s.vitals, s.damageTypes, s.channels)
  }

  /**
   * What changed from [saved] to [current], one line per kind that differs, or an empty list. Order
   * counts: a reordering is a change too.
   */
  fun diff(saved: SchemaNames, current: SchemaNames): List<String> = buildList {
    fun kind(label: String, was: List<String>, now: List<String>) {
      if (was == now) return
      val added = now - was.toSet()
      val removed = was - now.toSet()
      val parts = buildList {
        if (added.isNotEmpty()) add("added ${added.joinToString(", ")}")
        if (removed.isNotEmpty()) add("removed ${removed.joinToString(", ")}")
        if (added.isEmpty() && removed.isEmpty()) add("reordered")
      }
      add("$label: ${parts.joinToString("; ")}")
    }
    kind("stats", saved.stats, current.stats)
    kind("vitals", saved.vitals, current.vitals)
    kind("damage types", saved.damageTypes, current.damageTypes)
    kind("channels", saved.channels, current.channels)
  }

  /** The refusal to start with a changed schema, or null when [saved] matches [current]. */
  fun mismatch(saved: String, current: SchemaNames): String? {
    val changes = diff(decode(saved), current)
    if (changes.isEmpty()) return null
    return "The world's schema in MODULE.gups differs from the one this database was started with:\n" +
      changes.joinToString("\n") { "  $it" } +
      "\nSaved characters and items can't follow a schema change. Restore the old schema, or start with --wipe to delete every character and item and begin again."
  }
}
