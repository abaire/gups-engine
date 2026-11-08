package gups.core.world

import gups.core.AttrValue
import gups.core.model.Direction
import gups.core.model.Mob
import gups.core.model.Player

/** Converts an [AttrValue] to a plain value (`Long`, `String`, `Boolean`, `List`, `Map`). */
fun AttrValue.toPlain(): Any =
  when (this) {
    is AttrValue.I -> v
    is AttrValue.S -> v
    is AttrValue.B -> v
    is AttrValue.L -> v.map { it.toPlain() }
    is AttrValue.M -> v.mapValues { it.value.toPlain() }
  }

/** A plugin adjuster that recomputes a mob's owned behavior list. */
fun interface OwnedBehaviorAdjuster {
  fun adjust(world: World, mob: Mob, owner: Player?, behaviors: List<Behavior>): List<Behavior>
}

/** The behavior as a plain map: "kind" plus its builtin arguments by name. */
fun Behavior.descriptor(): Map<String, Any?> =
  when (this) {
    is Wander ->
      mapOf(
        "kind" to "wander",
        "radius" to radius.toLong(),
        "chance" to chance.toLong(),
        "priority" to priority.toLong(),
      )
    is Aggressive ->
      buildMap {
        put("kind", "aggressive")
        put("toward", toward.patterns)
        put("unless_level_above", unlessLevelAbove?.toLong())
        put("unless_level_below", unlessLevelBelow?.toLong())
        put("chance", chance.toLong())
        dispositionBelow?.let { put("disposition_below", it.toLong()) }
        prefer?.let { put("prefer", it.toPlain()) }
        put("priority", priority.toLong())
      }
    is Assist ->
      buildMap {
        put("kind", "assist")
        put("allies", allies.patterns)
        dispositionAbove?.let { put("disposition_above", it.toLong()) }
        prefer?.let { put("prefer", it.toPlain()) }
        put("priority", priority.toLong())
      }
    is Retarget ->
      buildMap {
        put("kind", "retarget")
        put("prefer", prefer.toPlain())
        put("toward", toward.patterns)
        dispositionBelow?.let { put("disposition_below", it.toLong()) }
        put("chance", chance.toLong())
        put("priority", priority.toLong())
      }
    is Flee ->
      mapOf(
        "kind" to "flee",
        "below" to below.mapValues { it.value.toLong() },
        "priority" to priority.toLong(),
      )
    is Guard ->
      buildMap {
        put("kind", "guard")
        put("exit", exit.name)
        put("allies", allies.patterns)
        dispositionAbove?.let { put("disposition_above", it.toLong()) }
      }
    is Follow ->
      buildMap {
        put("kind", "follow")
        put("target", target.patterns)
        dispositionAbove?.let { put("disposition_above", it.toLong()) }
        put("priority", priority.toLong())
      }
    is Pursue ->
      buildMap {
        put("kind", "pursue")
        put("target", target.patterns)
        dispositionBelow?.let { put("disposition_below", it.toLong()) }
        put("priority", priority.toLong())
        // range's null is "unlimited", not the default, so the key is always written.
        put("range", range?.toLong())
      }
    Sentinel -> mapOf("kind" to "sentinel")
    is Scavenge -> mapOf("kind" to "scavenge", "priority" to priority.toLong())
    is Heel -> mapOf("kind" to "heel", "priority" to priority.toLong())
    is Defend -> mapOf("kind" to "defend", "group" to group, "priority" to priority.toLong())
    else -> throw IllegalArgumentException("Unknown behavior: $this")
  }

private val ALLOWED_KEYS =
  mapOf(
    "wander" to setOf("kind", "radius", "chance", "priority"),
    "aggressive" to
      setOf(
        "kind",
        "toward",
        "unless_level_above",
        "unless_level_below",
        "chance",
        "disposition_below",
        "prefer",
        "priority",
      ),
    "assist" to setOf("kind", "allies", "disposition_above", "prefer", "priority"),
    "retarget" to setOf("kind", "prefer", "toward", "disposition_below", "chance", "priority"),
    "flee" to setOf("kind", "below", "priority"),
    "guard" to setOf("kind", "exit", "allies", "disposition_above"),
    "follow" to setOf("kind", "target", "disposition_above", "priority"),
    "pursue" to setOf("kind", "target", "disposition_below", "priority", "range"),
    "sentinel" to setOf("kind"),
    "scavenge" to setOf("kind", "priority"),
    "heel" to setOf("kind", "priority"),
    "defend" to setOf("kind", "group", "priority"),
  )

private val COMBAT_BEHAVIORS = setOf("defend", "aggressive", "assist", "retarget", "flee")

private val FILTER_KEYWORDS = setOf("players", "mobs", "all", "anyone")

// A plugin has no package for a `:name` pattern to be relative to, so only absolute patterns and
// bare names, which match in any package, are accepted.
private val LABEL_PATTERN = Regex("(//[a-z0-9_/]+:)?[A-Za-z0-9_.*\\-]+")

/** Builds a behavior from [d]; throws IllegalArgumentException naming the problem. */
fun World.behaviorOf(d: Map<String, Any?>): Behavior {
  val kind =
    d["kind"] as? String
      ?: throw IllegalArgumentException("descriptor must contain a string 'kind'")
  val allowed =
    ALLOWED_KEYS[kind] ?: throw IllegalArgumentException("unknown behavior kind '$kind'")

  if (kind in COMBAT_BEHAVIORS && Feature.COMBAT !in features) {
    throw IllegalArgumentException("behavior '$kind' requires combat feature")
  }

  val extra = d.keys - allowed
  if (extra.isNotEmpty()) {
    throw IllegalArgumentException("unknown key '${extra.first()}' for behavior '$kind'")
  }

  fun longArg(key: String, required: Boolean = true): Long? {
    if (key !in d) {
      if (required) throw IllegalArgumentException("missing required key '$key' for '$kind'")
      return null
    }
    val v =
      d[key]
        ?: if (required) throw IllegalArgumentException("key '$key' cannot be null for '$kind'")
        else return null
    if (v !is Long) {
      val typeName = v::class.simpleName ?: "null"
      throw IllegalArgumentException("key '$key' must be a Long, got $typeName")
    }
    return v
  }

  fun intArg(key: String, required: Boolean = true): Int? = longArg(key, required)?.toInt()

  fun priorityArg(default: Int): Int {
    if ("priority" !in d || d["priority"] == null) return default
    val v = d["priority"]
    if (v !is Long) {
      val typeName = v?.let { it::class.simpleName } ?: "null"
      throw IllegalArgumentException("priority must be a Long, got $typeName")
    }
    return v.toInt()
  }

  fun actorFilterArg(key: String, default: List<String>? = null): ActorFilter {
    if (key !in d || d[key] == null) {
      if (default != null) return ActorFilter(default)
      throw IllegalArgumentException("missing required key '$key' for '$kind'")
    }
    val v = d[key]
    if (v !is List<*>) throw IllegalArgumentException("key '$key' must be a list of strings")
    if (v.isEmpty()) throw IllegalArgumentException("key '$key' must not be empty")
    val patterns = mutableListOf<String>()
    for (item in v) {
      if (item !is String) throw IllegalArgumentException("elements of '$key' must be strings")
      if (item !in FILTER_KEYWORDS && !LABEL_PATTERN.matches(item)) {
        throw IllegalArgumentException(
          "invalid label pattern '$item' in '$key'; use //pkg:name* or a bare name* (any package)"
        )
      }
      patterns.add(item)
    }
    return ActorFilter(patterns)
  }

  fun preferArg(): AttrValue? {
    val raw = d["prefer"] ?: return null
    var count = 0
    fun parse(v: Any?, depth: Int): AttrValue {
      count++
      if (count > 64) {
        throw IllegalArgumentException("prefer for '$kind' may contain at most 64 values")
      }
      return when (v) {
        is Long -> AttrValue.I(v)
        is String -> AttrValue.S(v)
        is Boolean -> AttrValue.B(v)
        is List<*> -> {
          if (depth >= 4) {
            throw IllegalArgumentException("prefer for '$kind' may nest at most 4 deep")
          }
          AttrValue.L(v.map { parse(it, depth + 1) })
        }
        is Map<*, *> -> {
          if (depth >= 4) {
            throw IllegalArgumentException("prefer for '$kind' may nest at most 4 deep")
          }
          AttrValue.M(
            v.entries.associate { (k, item) ->
              val key =
                k as? String
                  ?: throw IllegalArgumentException("prefer dict keys for '$kind' must be Strings")
              key to parse(item, depth + 1)
            }
          )
        }
        else -> {
          val typeName = v?.let { it::class.simpleName } ?: "null"
          throw IllegalArgumentException(
            "prefer for '$kind' must be a Long, String, Boolean, List or Map, got $typeName"
          )
        }
      }
    }
    return parse(raw, 0)
  }

  return when (kind) {
    "wander" -> {
      val radius = intArg("radius", required = true)!!
      if (radius < 0) throw IllegalArgumentException("radius cannot be negative")
      val chance = intArg("chance", required = true)!!
      if (chance !in 0..100) throw IllegalArgumentException("chance must be 0..100")
      val priority = priorityArg(PRIORITY_WANDER)
      Wander(radius, chance, priority)
    }
    "aggressive" -> {
      val toward = actorFilterArg("toward", default = listOf("players"))
      val unlessAbove = intArg("unless_level_above", required = false)
      val unlessBelow = intArg("unless_level_below", required = false)
      val chance = intArg("chance", required = false) ?: 100
      if (chance !in 0..100) throw IllegalArgumentException("chance must be 0..100")
      val dispositionBelow = intArg("disposition_below", required = false)
      if (dispositionBelow != null && dispositionBelow !in -100..100) {
        throw IllegalArgumentException("disposition_below must be in -100..100")
      }
      val prefer = preferArg()
      val priority = priorityArg(PRIORITY_AGGRESSIVE)
      Aggressive(toward, unlessAbove, unlessBelow, chance, dispositionBelow, prefer, priority)
    }
    "assist" -> {
      val allies = actorFilterArg("allies")
      val dispositionAbove = intArg("disposition_above", required = false)
      if (dispositionAbove != null && dispositionAbove !in -100..100) {
        throw IllegalArgumentException("disposition_above must be in -100..100")
      }
      val prefer = preferArg()
      val priority = priorityArg(PRIORITY_ASSIST)
      Assist(allies, dispositionAbove, prefer, priority)
    }
    "retarget" -> {
      val prefer =
        preferArg()
          ?: throw IllegalArgumentException("missing required key 'prefer' for 'retarget'")
      val toward = actorFilterArg("toward", default = listOf("players"))
      val dispositionBelow = intArg("disposition_below", required = false)
      if (dispositionBelow != null && dispositionBelow !in -100..100) {
        throw IllegalArgumentException("disposition_below must be in -100..100")
      }
      val chance = intArg("chance", required = false) ?: 100
      if (chance !in 0..100) throw IllegalArgumentException("chance must be 0..100")
      val priority = priorityArg(PRIORITY_RETARGET)
      Retarget(prefer, toward, dispositionBelow, chance, priority)
    }
    "flee" -> {
      val v =
        d["below"] ?: throw IllegalArgumentException("missing required key 'below' for 'flee'")
      if (v !is Map<*, *>) throw IllegalArgumentException("below must be a Map<String, Long>")
      if (v.isEmpty()) throw IllegalArgumentException("below must name at least one vital")
      val belowMap = mutableMapOf<String, Int>()
      for ((k, valItem) in v) {
        if (k !is String) throw IllegalArgumentException("vital name in below must be a String")
        if (valItem !is Long) throw IllegalArgumentException("vital value in below must be a Long")
        if (schema.vitalNames.isNotEmpty() && k !in schema.vitalNames) {
          throw IllegalArgumentException("unknown vital '$k' in below")
        }
        if (valItem !in 0L..100L)
          throw IllegalArgumentException("vital percentage for '$k' must be in 0..100")
        belowMap[k] = valItem.toInt()
      }
      val priority = priorityArg(PRIORITY_FLEE)
      Flee(belowMap, priority)
    }
    "guard" -> {
      val exitStr = d["exit"] as? String ?: throw IllegalArgumentException("exit must be a string")
      val dir = Direction.parse(exitStr)
      val allies = actorFilterArg("allies", default = listOf("mobs"))
      val dispositionAbove = intArg("disposition_above", required = false)
      if (dispositionAbove != null && dispositionAbove !in -100..100) {
        throw IllegalArgumentException("disposition_above must be in -100..100")
      }
      Guard(dir, allies, dispositionAbove)
    }
    "follow" -> {
      val target = actorFilterArg("target")
      val dispositionAbove = intArg("disposition_above", required = false)
      if (dispositionAbove != null && dispositionAbove !in -100..100) {
        throw IllegalArgumentException("disposition_above must be in -100..100")
      }
      val priority = priorityArg(PRIORITY_FOLLOW)
      Follow(target, dispositionAbove, priority)
    }
    "pursue" -> {
      val target = actorFilterArg("target")
      val dispositionBelow = intArg("disposition_below", required = false)
      if (dispositionBelow != null && dispositionBelow !in -100..100) {
        throw IllegalArgumentException("disposition_below must be in -100..100")
      }
      val priority = priorityArg(PRIORITY_PURSUE)
      // A missing key means the default range; a present null means "unlimited".
      val range =
        if ("range" in d) {
          when (val v = d["range"]) {
            null -> null
            is Long -> {
              if (v < 1) throw IllegalArgumentException("range must be at least 1")
              v.toInt()
            }
            else ->
              throw IllegalArgumentException(
                "range must be a Long or null, got ${v::class.simpleName}"
              )
          }
        } else PURSUE_DEFAULT_RANGE
      Pursue(target, dispositionBelow, priority, range)
    }
    "sentinel" -> Sentinel
    "scavenge" -> {
      val priority = priorityArg(PRIORITY_SCAVENGE)
      Scavenge(priority)
    }
    "heel" -> {
      val priority = priorityArg(PRIORITY_HEEL)
      Heel(priority)
    }
    "defend" -> {
      val group =
        if ("group" in d) {
          val g = d["group"]
          if (g !is Boolean) throw IllegalArgumentException("group must be a Boolean")
          g
        } else {
          true
        }
      val priority = priorityArg(PRIORITY_DEFEND)
      Defend(group, priority)
    }
    else -> error("unhandled kind '$kind'")
  }
}
