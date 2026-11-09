package gups.sample.bluemage

import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginRulesType
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginTraitCall
import gups.pluginapi.PluginTraitEvent
import gups.pluginapi.PluginTraitHandler
import gups.pluginapi.PluginWorld

/**
 * Sample plugin showing trait callbacks and a plugin-declared rules export: a blue mage learns
 * skills tagged `blue` by recognizing them when mobs use them. The trait handles
 * `SKILL_RECOGNIZED`, the learning chance is tunable through the optional rules export
 * `bluemage_learn_chance`, and `bluemagic` lists what the character learned this way.
 */
class BlueMagePlugin : GupsPlugin {
  override val id = "bluemage"
  override val pluginApiVersion = PluginApi.VERSION
  override val requiredFeatures: Set<String> = setOf("skills")
  override val doc = "Blue mage: learns marked skills by recognizing them when mobs use them."

  override fun register(r: PluginRegistry) {
    // A plugin-declared export: a world's rules may define it to tune the chance, and a world that
    // doesn't gets the plugin's own formula.
    r.addRulesExport(
      "bluemage_learn_chance",
      "The percent chance, before the 0-33 clamp, that a blue mage learns a skill they recognized. " +
        "ctx.base is the plugin's own value; ctx.args has was_target and power.",
      PluginRulesType.INT,
    )

    r.addTrait(
      PluginTrait(
        name = "blue_mage",
        label = "Blue mage",
        doc = "Learns marked skills by watching mobs use them.",
        on = mapOf(PluginTraitEvent.SKILL_RECOGNIZED to PluginTraitHandler(::onRecognized)),
      )
    )

    r.addBuiltin(
      name = "awaken",
      doc = "Makes an actor a blue mage; returns None, or the reason it was refused.",
      params = listOf(PluginParam("actor", "actor", "The actor to awaken.")),
      returnType = "str | None",
    ) { world, pos, _ ->
      val actor =
        pos[0] as? PluginActor
          ?: throw IllegalArgumentException("bluemage.awaken(actor): expected an actor")
      world.grantTrait(actor, TRAIT)
    }

    r.addCommand(
      name = "bluemagic",
      lane = PluginLane.FAST,
      role = PluginRole.PLAYER,
      doc = "Lists the skills you learned by watching.",
    ) { call ->
      val world = call.world
      val text =
        when {
          !call.actor.hasTrait(TRAIT) -> "You are not a blue mage."
          learned(world, call.actor).isEmpty() -> "You haven't learned any blue magic."
          else ->
            (listOf("Blue magic you have learned:") +
                learned(world, call.actor).map { "  ${world.skill(it)?.name ?: it}" })
              .joinToString("\n")
        }
      world.send(call.actor, text)
    }
  }

  private fun learned(world: PluginWorld, actor: PluginActor): List<String> =
    (world.getAttr(actor, LEARNED, emptyList<Any?>()) as? List<*>)?.filterIsInstance<String>()
      ?: emptyList()

  private fun onRecognized(call: PluginTraitCall) {
    val world = call.world
    val bearer = call.bearer
    val source = call.fields["source"] as? PluginActor ?: return
    val label = call.fields["skill"] as? String ?: return
    val outcome = call.fields["outcome"] as? String
    if (source.isPlayer || outcome != "success" && outcome != "resist") return
    val skill = world.skill(label) ?: return
    if ("blue" !in skill.tags || label in bearer.skills) return

    val wasTarget = (call.fields["target"] as? PluginActor)?.id == bearer.id
    val power = call.fields["power"] as? Long ?: 100L
    val base =
      1L + (if (wasTarget) 10L else 0L) -
        5L * maxOf(0, source.level - bearer.level) -
        (power - 100L) / 10L
    val chance =
      (world.rules(
          "bluemage_learn_chance",
          mapOf(
            "actor" to bearer,
            "source" to source,
            "skill" to skill,
            "base" to base,
            "power" to power,
            "was_target" to wasTarget,
          ),
          base,
        ) as Long)
        .coerceIn(0L, MAX_CHANCE)
        .toInt()
    if (!world.rng.chance(chance)) return

    // A LEARN restriction (a class that can't use the skill) refuses quietly: the blue mage just
    // doesn't learn it.
    if (world.setSkill(bearer, label, LEARNED_PROFICIENCY) != null) return
    world.send(bearer, "You grasp the secret of ${skill.name}!")
    world.setAttr(bearer, LEARNED, learned(world, bearer) + label)
  }

  private companion object {
    const val TRAIT = "blue_mage"
    const val LEARNED = "learned"
    const val MAX_CHANCE = 33L
    const val LEARNED_PROFICIENCY = 10
  }
}
