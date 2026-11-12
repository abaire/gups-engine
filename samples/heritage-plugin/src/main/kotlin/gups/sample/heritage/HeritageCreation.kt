package gups.sample.heritage

import gups.pluginapi.PluginActor
import gups.pluginapi.PluginChoice
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginCreationAttempt
import gups.pluginapi.PluginWorld

/** The heritage race and class choices, their availability rules and the stats step. */
open class HeritageCreation : PluginCreation {
  override val choices: List<PluginChoice> =
    listOf(
      PluginChoice("race", "Race", HeritagePlugin.RACE_OPTIONS),
      PluginChoice("class", "Class", HeritagePlugin.CLASS_OPTIONS),
    )

  override val steps = listOf(StatsStep())

  override fun available(
    world: PluginWorld,
    choice: String,
    answers: Map<String, String>,
  ): List<String> =
    when (choice) {
      "race" -> listOf("human", "elf", "dwarf")
      "class" ->
        if (answers["race"] == "dwarf") listOf("warrior", "cleric")
        else listOf("warrior", "mage", "cleric")
      else -> emptyList()
    }

  override fun validate(world: PluginWorld, answers: Map<String, String>): String? =
    if (answers["race"] == "dwarf" && answers["class"] == "mage") {
      "Dwarves cannot be mages."
    } else null

  override fun startRoom(world: PluginWorld, answers: Map<String, String>): String? = null

  override fun prepare(world: PluginWorld, actor: PluginActor, attempt: PluginCreationAttempt) {
    @Suppress("UNCHECKED_CAST")
    val spend = (attempt.state["spend"] as? Map<String, Int>) ?: emptyMap()
    for ((stat, delta) in spend) {
      if (delta != 0) {
        world.setBaseStat(actor, stat, actor.baseStat(stat) + delta)
      }
    }
  }

  override fun created(world: PluginWorld, actor: PluginActor, newCharacter: Boolean) {
    grantStarterSkills(world, actor)
  }

  /** Grants the current class's starter skills the actor lacks; returns the labels granted. */
  fun grantStarterSkills(world: PluginWorld, actor: PluginActor): List<String> =
    STARTER_SKILLS[actor.choices["class"]].orEmpty().filter { label ->
      label !in actor.skills && tryGrant(world, actor, label)
    }

  private fun tryGrant(world: PluginWorld, actor: PluginActor, label: String): Boolean =
    try {
      // A learn refusal leaves the skill out silently: the class still changed.
      world.setSkill(actor, label, STARTER_PROFICIENCY) == null
    } catch (_: IllegalArgumentException) {
      false // the world doesn't define this skill
    }

  companion object {
    const val STARTER_PROFICIENCY = 25
    val STARTER_SKILLS: Map<String, List<String>> =
      mapOf(
        "mage" to listOf("//skills/heritage_skills:magic_missile"),
        "cleric" to listOf("//skills/heritage_skills:minor_heal"),
      )
  }
}
