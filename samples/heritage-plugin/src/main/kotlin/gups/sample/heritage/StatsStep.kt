package gups.sample.heritage

import gups.pluginapi.PluginCreationAttempt
import gups.pluginapi.PluginCreationStep
import gups.pluginapi.PluginStepReply
import gups.pluginapi.PluginStepView
import gups.pluginapi.PluginWorld

/**
 * Character creation step for custom stat allocation using point-buy.
 *
 * Players receive a budget of points to adjust their previewed stats up or down.
 */
class StatsStep : PluginCreationStep {
  override val id = "stats"
  override val title = "Stats"

  companion object {
    const val BUDGET = 6
    const val FLOOR = -2
    const val CAP = 4
    val STATS = listOf("str", "dex", "con", "int", "wis", "cha")
    private val COMMAND_REGEX = """^(\w+)\s*([+-]\d+)$""".toRegex(RegexOption.IGNORE_CASE)
  }

  @Suppress("UNCHECKED_CAST")
  private fun spendMap(attempt: PluginCreationAttempt): Map<String, Int> =
    (attempt.state["spend"] as? Map<String, Int>) ?: emptyMap()

  override fun show(world: PluginWorld, attempt: PluginCreationAttempt): PluginStepView {
    val spend = spendMap(attempt)
    val left = BUDGET - spend.values.sum()
    val lines = mutableListOf<String>()
    for (stat in STATS) {
      val base = attempt.preview.baseStat(stat)
      val delta = spend[stat] ?: 0
      val finalVal = base + delta
      val sign = if (delta >= 0) "+$delta" else "$delta"
      val name = stat.replaceFirstChar { it.uppercase() }
      lines += " $name $base → $finalVal ($sign)"
    }
    lines +=
      "Points left: $left. Type '<stat> +N', '<stat> -N', 'reset' or 'done'. Unspent points are lost."

    val gmcp =
      mapOf(
        "budget" to BUDGET,
        "left" to left,
        "stats" to
          STATS.map { stat ->
            mapOf(
              "name" to stat,
              "base" to attempt.preview.baseStat(stat),
              "delta" to (spend[stat] ?: 0),
            )
          },
      )
    return PluginStepView(lines.joinToString("\n"), gmcp)
  }

  override fun input(
    world: PluginWorld,
    attempt: PluginCreationAttempt,
    line: String,
  ): PluginStepReply {
    val trimmed = line.trim()
    if (trimmed.equals("done", ignoreCase = true)) {
      return PluginStepReply.Done
    }
    if (trimmed.equals("reset", ignoreCase = true)) {
      attempt.state.remove("spend")
      return PluginStepReply.Stay()
    }
    if (trimmed.equals("help", ignoreCase = true)) {
      return PluginStepReply.Stay(
        "Allocate up to $BUDGET points across stats. Each +1 costs 1 point; each -1 refunds 1 point. Valid stats: ${STATS.joinToString(", ")}."
      )
    }

    val match =
      COMMAND_REGEX.matchEntire(trimmed)
        ?: return PluginStepReply.Stay("Type '<stat> +N', '<stat> -N', 'reset' or 'done'.")

    val prefix = match.groupValues[1].lowercase()
    val amount = match.groupValues[2].toInt()

    val matchedStats = STATS.filter { it.startsWith(prefix) }
    if (matchedStats.isEmpty()) {
      return PluginStepReply.Stay(
        "Unknown stat '$prefix'. Valid stats: ${STATS.joinToString(", ")}."
      )
    }
    if (matchedStats.size > 1) {
      return PluginStepReply.Stay(
        "Ambiguous stat '$prefix' matches: ${matchedStats.joinToString(", ")}."
      )
    }
    val stat = matchedStats.single()

    val spend = spendMap(attempt).toMutableMap()
    val currentDelta = spend[stat] ?: 0
    val newDelta = currentDelta + amount

    if (newDelta < FLOOR) {
      return PluginStepReply.Stay("Cannot lower $stat below $FLOOR from base.")
    }
    if (newDelta > CAP) {
      return PluginStepReply.Stay("Cannot raise $stat above +$CAP from base.")
    }

    val currentSpent = spend.values.sum()
    val newSpent = currentSpent + amount
    if (newSpent > BUDGET) {
      val left = BUDGET - currentSpent
      return PluginStepReply.Stay("Not enough points left: need $amount, have $left.")
    }

    if (newDelta == 0) {
      spend.remove(stat)
    } else {
      spend[stat] = newDelta
    }
    attempt.state["spend"] = spend
    return PluginStepReply.Stay()
  }

  override fun summary(world: PluginWorld, attempt: PluginCreationAttempt): String {
    val spend = spendMap(attempt)
    return STATS.joinToString(" ") { stat ->
      val base = attempt.preview.baseStat(stat)
      val finalVal = base + (spend[stat] ?: 0)
      "${stat.replaceFirstChar { it.uppercase() }} $finalVal"
    }
  }
}
