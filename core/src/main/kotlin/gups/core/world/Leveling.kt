package gups.core.world

import gups.core.model.Actor
import gups.core.model.LevelGain
import gups.core.model.Player
import gups.core.rules.RulesContext

object LevelingMsg : MsgGroup(Feature.LEVELING) {
  val SCORE_LEVEL = msg("leveling.score", "Level {level} ({xp} xp)", "level,xp")
  val GAIN = msg("leveling.gain", "You gain {amount} experience.", "amount", "amount")
  val LEVEL_UP = msg("leveling.level_up", "You have reached level {level}!", "level", "level")
}

/** The most level steps one call applies, and the most [pendingLevels] counts. */
const val MAX_LEVEL_STEPS = 100

/**
 * Calculates how many pending levels [a] is currently eligible to advance.
 *
 * Counts consecutive step offsets `k >= 0` where `a.xp >= rules.xpToLevel(a.level + k)`, up to
 * [MAX_LEVEL_STEPS]. The loop stops at the first step offset where the threshold is not strictly
 * increasing (emitting a `rules_error` alert), or when the threshold is not reached. Returns 0 if
 * leveling is disabled or if the threshold is never reached.
 */
fun World.pendingLevels(a: Actor): Int {
  if (Feature.LEVELING !in features) return 0
  var count = 0
  var prev: Long? = null
  for (k in 0 until MAX_LEVEL_STEPS) {
    val lvl = a.level + k
    val threshold = rules.xpToLevel(lvl)
    if (prev != null && threshold <= prev) {
      diagnose(
        "rules",
        "rules.xp_to_level for level $lvl ($threshold) is not strictly greater than previous level ($prev)",
      )
      alert(
        "rules_error",
        "rules.xp_to_level for level $lvl ($threshold) is not strictly greater than previous level ($prev)",
      )
      break
    }
    if (a.xp < threshold) break
    count++
    prev = threshold
  }
  return count
}

/**
 * Applies a single level increase to [a], if eligible. This is the only code that raises a level.
 *
 * If the actor has no pending levels, returns false and changes nothing. Otherwise:
 * 1. Increments the actor's level by one.
 * 2. Calls `rules.onLevelUp` with the actor (at its new level) and world RNG.
 * 3. Treats a rules error or null result as no gains; the level stays raised.
 * 4. Adds stat deltas to base stats and max deltas to base vital maxima. Adds current deltas to
 *    current vitals, then caps every current vital at its new effective maximum. Zero deltas and
 *    names the actor does not have are ignored.
 * 5. Records the applied stat and max deltas in `levelGains` under the reached level, replacing any
 *    older entry. A level that applied neither drops any older entry, so a later level loss never
 *    unwinds gains this step did not give. Current vital deltas are never recorded.
 * 6. Sends the level-up message to the actor.
 * 7. Asks for the actor's condition once if any vital changed.
 * 8. Marks the actor dirty, requests an immediate checkpoint if a player, and returns true.
 */
fun World.levelUp(a: Actor): Boolean {
  if (pendingLevels(a) <= 0) return false

  a.level += 1

  val result =
    try {
      rules.onLevelUp(RulesContext(this, a))
    } catch (e: Exception) {
      diagnose("rules", "rules.on_level_up failed: ${e.message}", e)
      alert("rules_error", "rules.on_level_up failed; using its neutral result for this call")
      null
    }

  val stats = result?.stats.orEmpty().filter { (name, d) -> d != 0 && schema.hasStat(name) }
  val max = result?.max.orEmpty().filter { (name, d) -> d != 0 && name in a.vitalMap }
  val current = result?.current.orEmpty().filter { (name, d) -> d != 0 && name in a.vitalMap }

  for ((name, delta) in stats) a.baseStats[name] = (a.baseStats[name] ?: 0) + delta
  for ((name, delta) in max) a.vitalMap.getValue(name).max += delta
  applyVitalDeltas(a, current, null)
  val capped = capVitals(a)

  if (stats.isNotEmpty() || max.isNotEmpty()) {
    a.levelGains[a.level] = LevelGain(stats = stats, max = max)
  } else {
    a.levelGains.remove(a.level)
  }

  tell(a, LevelingMsg.LEVEL_UP, "level" to a.level)

  if (max.isNotEmpty() || current.isNotEmpty() || capped) refreshCondition(a)

  a.dirty = true
  if (a is Player) requestImmediate("level")
  return true
}
