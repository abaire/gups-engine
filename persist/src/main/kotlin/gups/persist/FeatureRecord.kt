package gups.persist

import gups.core.world.Feature
import gups.core.world.Features

// The features a database was started with. Saved actors carry feature state (levels, XP, skills,
// track positions) that a different feature set can't follow, so a later start with another set
// refuses to run on the same data, like a schema change.

object FeatureRecord {
  fun encode(features: Features): String = features.ids.joinToString(",")

  fun decode(text: String): Features = Features.parse(text)

  /** What changed from [saved] to [current], one line per direction that differs. */
  fun diff(saved: Features, current: Features): List<String> = buildList {
    val added = Feature.entries.filter { it in current && it !in saved }
    val removed = Feature.entries.filter { it in saved && it !in current }
    if (added.isNotEmpty()) add("added ${added.joinToString(", ") { it.id }}")
    if (removed.isNotEmpty()) add("removed ${removed.joinToString(", ") { it.id }}")
  }

  /** The refusal to start with changed features, or null when [saved] matches [current]. */
  fun mismatch(saved: String, current: Features): String? {
    // An id this engine doesn't know was on when the data was saved, and is off now.
    val unknown =
      saved.split(',').map { it.trim() }.filter { it.isNotEmpty() && Feature.byId(it) == null }
    val changes =
      diff(decode(saved), current) + unknown.distinct().map { "unknown to this engine: $it" }
    if (changes.isEmpty()) return null
    return "The world's features in MODULE.gups differ from the ones this database was started with:\n" +
      changes.joinToString("\n") { "  features: $it" } +
      "\nSaved characters and items can't follow a feature change. Restore the old features, or start with --wipe to delete every character and item and begin again."
  }
}
