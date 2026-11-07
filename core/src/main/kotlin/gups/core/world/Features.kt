package gups.core.world

/** The engine's optional feature modules; a world turns them on in `MODULE.gups`. */
enum class Feature(val id: String, val requires: Set<Feature>) {
  COMBAT("combat", emptySet()),
  TRACK("track", setOf(COMBAT)),
  SKILLS("skills", emptySet()),
  LEVELING("leveling", emptySet()),
  STANDING("standing", emptySet());

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

/**
 * The features an API entry needs: any one of [features] when [all] is false, every one of them
 * when it is true. A single-feature requirement is either.
 */
data class FeatureRequirement(val features: Set<Feature>, val all: Boolean = false) {
  init {
    require(features.isNotEmpty()) { "a feature requirement names at least one feature" }
  }

  fun satisfiedBy(enabled: Features): Boolean =
    if (all) features.all { it in enabled } else features.any { it in enabled }

  /** Feature ids in `Feature` declaration order. */
  val ids: List<String> = Feature.entries.filter { it in features }.map { it.id }

  /** "combat", "combat or skills", "combat and skills" (declaration order). */
  val text: String = ids.joinToString(if (all) " and " else " or ")

  /** "feature", or "features" when every one of several is needed. */
  val noun: String = if (all && features.size > 1) "features" else "feature"

  companion object {
    fun of(feature: Feature): FeatureRequirement = FeatureRequirement(setOf(feature))

    fun anyOf(vararg features: Feature): FeatureRequirement =
      FeatureRequirement(features.toSet(), all = false)

    fun allOf(vararg features: Feature): FeatureRequirement =
      FeatureRequirement(features.toSet(), all = true)
  }
}
