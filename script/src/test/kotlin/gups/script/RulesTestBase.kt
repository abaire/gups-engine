package gups.script

import gups.script.testing.TestWorlds
import gups.script.testing.tempWorld

/** Shared fixtures for the rules tests. */
abstract class RulesTestBase {
  protected val rulesPkg = "rules/package.star" to TestWorlds.RULES_PACKAGE

  /** A world whose rules are the template's with the functions of [combat] replaced. */
  protected fun world(
    combat: String,
    rooms: String = DEFAULT_ROOMS,
    vararg more: Pair<String, String>,
  ) =
    ScriptFixture(
      mapOf(
        rulesPkg,
        "rules/combat.star" to TestWorlds.rules(combat),
        "zones/z/package.star" to zonePkg(),
        "zones/z/rooms.star" to rooms,
      ) + more,
      load = listOf("zones/z", "rules"),
    )

  protected fun loadWith(combat: String): LoadResult {
    val root =
      tempWorld(
        mapOf(
          rulesPkg,
          "rules/combat.star" to combat,
          "zones/z/package.star" to zonePkg(),
          "zones/z/rooms.star" to DEFAULT_ROOMS,
        )
      )
    return ScriptLoader(WorldDir(root), ScriptLimits.HARNESS).load(listOf("zones/z", "rules"))
  }

  companion object {
    const val DEFAULT_ROOMS = "room(\"start\")\nmob(\"rat\", vitals = vitals(hp = 100))\n"
  }
}
