package gups.core.world

/** The engine's optional feature modules; a world turns them on in `MODULE.gups`. */
enum class Feature(val id: String, val requires: Set<Feature>) {
  COMBAT("combat", emptySet()),
  TRACK("track", setOf(COMBAT)),
  SKILLS("skills", emptySet()),
  LEVELING("leveling", emptySet());

  companion object {
    fun byId(id: String): Feature? = entries.firstOrNull { it.id == id }
  }
}

/** The features a world has on. */
class Features(val enabled: Set<Feature>) {
  operator fun contains(f: Feature): Boolean = f in enabled

  /** The ids of the enabled features, in declaration order. */
  val ids: List<String>
    get() = Feature.entries.filter { it in enabled }.map { it.id }

  /** Enabled features whose requirements are off. */
  fun problems(): List<String> =
    Feature.entries
      .filter { it in enabled }
      .flatMap { f -> f.requires.filter { it !in enabled }.map { f to it } }
      .map { (f, need) -> "feature '${f.id}' needs the '${need.id}' feature" }

  override fun equals(other: Any?): Boolean = other is Features && other.enabled == enabled

  override fun hashCode(): Int = enabled.hashCode()

  override fun toString(): String = ids.joinToString(",")

  companion object {
    val ALL = Features(Feature.entries.toSet())

    /** Parses ids as stored in `meta.world_features`: comma separated, in any order. */
    fun parse(text: String): Features =
      Features(text.split(',').filter { it.isNotBlank() }.mapNotNull(Feature::byId).toSet())
  }
}
