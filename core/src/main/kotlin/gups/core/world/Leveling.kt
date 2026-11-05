package gups.core.world

import gups.core.model.Actor
import gups.core.model.LevelGain
import gups.core.model.Player
import gups.core.rules.RulesContext

/**
 * How earned levels are applied to actors: automatically upon eligibility or deferred until a
 * script advances them.
 */
enum class LevelMode {
  /**
   * Earned levels apply immediately. Experience awards call [levelUp] while the actor is eligible,
   * so kills, experience awards, and scripts share one progression path.
   */
  AUTO,

  /**
   * Experience awards only accumulate experience points. When an award causes pending levels to
   * rise from zero to more than zero, the actor is notified that they are ready to advance. Levels
   * are applied only when a script or command explicitly advances the actor.
   */
  DEFERRED,
}

object LevelingMsg : MsgGroup(Feature.LEVELING) {
  val SCORE_LEVEL = msg("leveling.score", "Level {level} ({xp} xp)", "level,xp")
  val GAIN = msg("leveling.gain", "You gain {amount} experience.", "amount", "amount")
  val LEVEL_UP = msg("leveling.level_up", "You have reached level {level}!", "level", "level")
  val LEVEL_DOWN =
    msg("leveling.level_down", "You have fallen to level {level}.", "level,count", "level")
  val READY = msg("leveling.ready", "You feel ready to advance.")
  val SCORE_READY = msg("leveling.score_ready", "You are ready to advance.")
}

/** The most level steps one call applies, and the most [pendingLevels] counts. */
const val MAX_LEVEL_STEPS = 100

/**
 * Calculates how many pending levels [a] is currently eligible to advance.
 *
 * Counts consecutive step offsets `k >= 0` where `a.xp >= rules.xpToLevel(c, a.level + k)` for a
 * rules context about [a], up to [MAX_LEVEL_STEPS]. The loop stops at the first step offset where
 * the threshold is not strictly increasing (emitting a `rules_error` alert at most once per
 * quarantine window), or when the threshold is not reached. The first threshold is compared with
 * the one for the level below, so an actor that already stepped past a non-increasing threshold is
 * owed nothing more. Returns 0 if leveling is disabled or if the threshold is never reached.
 */
fun World.pendingLevels(a: Actor): Int = countPendingLevels(a, MAX_LEVEL_STEPS)

private fun World.countPendingLevels(a: Actor, limit: Int): Int {
  if (Feature.LEVELING !in features) return 0
  val c = RulesContext(this, a)
  var count = 0
  var prev: Long? = if (a.level >= 2) rules.xpToLevel(c, a.level - 1) else null
  for (k in 0 until limit) {
    val lvl = a.level + k
    val threshold = rules.xpToLevel(c, lvl)
    if (prev != null && threshold <= prev) {
      rulesFault(
        "xp_to_level",
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
 * 5. Records the applied stat and max deltas as the entry in `levelGains` for the reached level.
 *    That entry is replaced, or removed if this level applied neither, so that losing this level
 *    later subtracts exactly what this step added. Entries for other levels are left alone, and
 *    current vital deltas are never recorded.
 * 6. Sends the level-up message to the actor.
 * 7. Asks for the actor's condition once if any vital changed.
 * 8. Dispatches `on_level_change` with `old_level = a.level - 1` and `new_level = a.level`.
 * 9. Marks the actor dirty, requests an immediate checkpoint if a player, and returns true.
 */
fun World.levelUp(a: Actor): Boolean {
  if (countPendingLevels(a, 1) <= 0) return false

  a.level += 1
  forgetActorSlots(a)
  refreshFit(a)

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

  val levelCtx = HookContext(actor = a, oldLevel = a.level - 1, newLevel = a.level)
  dispatch(HookName.ON_LEVEL_CHANGE, a, a.room, levelCtx)
  dispatchGlobal(HookName.ON_LEVEL_CHANGE, levelCtx)

  a.dirty = true
  if (a is Player) requestImmediate("level")
  return true
}

/**
 * Applies a single level increase to [a] via [levelUp] and notifies the actor.
 *
 * If the level was raised:
 * - If [a] is a [Player], requests an immediate GMCP `Char.Status` refresh.
 * - In [LevelMode.DEFERRED] mode, sends [LevelingMsg.READY] if additional levels remain pending.
 *   The ready notice is stateless: it fires only when levels remain pending after a level-up step
 *   or when an award first makes levels pending.
 *
 * Returns true if a level was applied, or false if the actor had no pending levels.
 */
fun World.levelUpAndNotify(a: Actor): Boolean {
  val raised = levelUp(a)
  if (raised) {
    if (a is Player) {
      refreshGmcp(a, "Char.Status")
    }
    if (levelMode == LevelMode.DEFERRED && countPendingLevels(a, 1) > 0) {
      tell(a, LevelingMsg.READY)
    }
  }
  return raised
}

/**
 * Awards [amount] experience points to [a].
 *
 * Ignored unless the leveling feature is enabled and [amount] is strictly positive. Adds [amount]
 * to the actor's experience and sends [LevelingMsg.GAIN].
 *
 * In [LevelMode.AUTO] mode, applies owed levels up to [MAX_LEVEL_STEPS] via [levelUp]; levels still
 * owed after that wait for the next award. Each step fires `on_level_change`, so it also stops
 * early if a hook takes the actor out of the world, such as by killing it. If [a] is a player whose
 * level changed, requests an immediate GMCP `Char.Status` refresh at the end.
 *
 * In [LevelMode.DEFERRED] mode, levels are not applied automatically. If [pendingLevels] rises from
 * zero to more than zero as a result of this award, sends [LevelingMsg.READY] to notify the actor
 * that advancement is available. Subsequent awards while levels are already pending do not repeat
 * the notification.
 */
fun World.awardXp(a: Actor, amount: Long) {
  if (Feature.LEVELING !in features || amount <= 0) return
  val hadPending = levelMode == LevelMode.DEFERRED && countPendingLevels(a, 1) > 0
  a.xp += amount
  tell(a, LevelingMsg.GAIN, "amount" to amount)
  when (levelMode) {
    LevelMode.AUTO -> {
      val initialLevel = a.level
      var steps = 0
      while (steps < MAX_LEVEL_STEPS && a.inWorld && levelUp(a)) {
        steps++
      }
      if (a is Player && a.level != initialLevel) {
        refreshGmcp(a, "Char.Status")
      }
    }
    LevelMode.DEFERRED -> {
      if (!hadPending && countPendingLevels(a, 1) > 0) {
        tell(a, LevelingMsg.READY)
      }
    }
  }
  a.dirty = true
}

/**
 * Lowers [a]'s level by one step. This is the only code that lowers a level. The caller guarantees
 * that `a.level >= 2`.
 *
 * The step proceeds in this order:
 * 1. Takes and removes `a.levelGains[a.level]`, if any. A level with no recorded entry unwinds
 *    nothing.
 * 2. Subtracts any recorded stats from base stats and recorded maxima from base vital maxima.
 * 3. Decrements `a.level` by one.
 * 4. Caps every current vital at its new effective maximum.
 * 5. Applies the "never owed the level just lost" rule: clamps `a.xp` to `min(a.xp,
 *    rules.xp_to_level(a.level) - 1)` (never below 0). This also removes any other pending levels.
 * 6. Asks condition once if any vital maximum changed or a current vital was capped.
 * 7. Dispatches `on_level_change` with `old_level = a.level + 1` and `new_level = a.level`.
 * 8. Marks the actor dirty.
 */
fun World.levelDownStep(a: Actor) {
  require(a.level >= 2) { "cannot step down below level 1" }

  val gain = a.levelGains.remove(a.level)
  if (gain != null) {
    for ((name, delta) in gain.stats) a.baseStats[name] = (a.baseStats[name] ?: 0) - delta
    for ((name, delta) in gain.max) a.vitalMap[name]?.let { it.max -= delta }
  }

  a.level -= 1
  forgetActorSlots(a)
  refreshFit(a)

  val capped = capVitals(a)

  a.xp = maxOf(0L, minOf(a.xp, rules.xpToLevel(RulesContext(this, a), a.level) - 1))

  if (gain?.max?.isNotEmpty() == true || capped) refreshCondition(a)

  val levelCtx = HookContext(actor = a, oldLevel = a.level + 1, newLevel = a.level)
  dispatch(HookName.ON_LEVEL_CHANGE, a, a.room, levelCtx)
  dispatchGlobal(HookName.ON_LEVEL_CHANGE, levelCtx)

  a.dirty = true
}

/**
 * Lowers [a]'s level by up to [count] steps, stopping at level 1, or early if an `on_level_change`
 * hook takes the actor out of the world.
 *
 * If leveling is disabled or [count] is not positive, returns 0 and changes nothing. If at least
 * one step was taken, sends [LevelingMsg.LEVEL_DOWN] to the actor once, after the last step's
 * `on_level_change`, and, for a player, requests an immediate GMCP `Char.Status` refresh and an
 * immediate checkpoint. Returns the number of levels actually lost.
 *
 * The rules are never called: the engine subtracts exactly what was stored in the level gain
 * records, so gains that used random numbers unwind exactly, and so do gains from a rules package
 * that has since changed.
 */
fun World.levelDown(a: Actor, count: Int = 1): Int {
  if (Feature.LEVELING !in features || count <= 0) return 0
  var steps = 0
  while (steps < count && a.level > 1 && a.inWorld) {
    levelDownStep(a)
    steps++
  }
  if (steps > 0) {
    tell(a, LevelingMsg.LEVEL_DOWN, "level" to a.level, "count" to steps)
    if (a is Player) {
      refreshGmcp(a, "Char.Status")
      requestImmediate("level")
    }
  }
  return steps
}

/**
 * Removes up to [amount] experience points from [a], returning the amount actually removed.
 *
 * If leveling is disabled or [amount] is not positive, returns 0. The actor's experience never goes
 * below 0. Removing experience never changes the actor's level and sends nothing.
 */
fun World.takeXp(a: Actor, amount: Long): Long {
  if (Feature.LEVELING !in features || amount <= 0L) return 0L
  val removed = minOf(amount, a.xp)
  if (removed > 0L) {
    a.xp -= removed
    a.dirty = true
  }
  return removed
}
