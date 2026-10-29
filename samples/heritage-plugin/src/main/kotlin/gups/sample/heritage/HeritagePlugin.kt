package gups.sample.heritage

import gups.pluginapi.GupsPlugin
import gups.pluginapi.PluginActor
import gups.pluginapi.PluginApi
import gups.pluginapi.PluginChoice
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginModifier
import gups.pluginapi.PluginModifierPoint
import gups.pluginapi.PluginOption
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRestrictionPoint
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginWorld

/** Fantasy race and class plugin for character creation, traits and restrictions. */
class HeritagePlugin : GupsPlugin {
  override val id = "heritage"
  override val pluginApiVersion = PluginApi.VERSION
  override val requiredFeatures = setOf("combat", "skills")
  override val doc = "Fantasy race and class choices, traits and restrictions."

  override fun register(r: PluginRegistry) {
    // ---- Traits -----------------------------------------------------------------------------
    r.addTrait(
      PluginTrait(
        name = "human",
        label = "Human",
        doc = "Adaptable and well-rounded.",
        statDeltas = mapOf("str" to 1, "dex" to 1, "con" to 1, "int" to 1, "wis" to 1),
      )
    )
    r.addTrait(
      PluginTrait(
        name = "elf",
        label = "Elf",
        doc = "Nimble and magical, but fragile.",
        statDeltas = mapOf("dex" to 2, "con" to -1),
        maxVitalDeltas = mapOf("mana" to 10),
      )
    )
    r.addTrait(
      PluginTrait(
        name = "dwarf",
        label = "Dwarf",
        doc = "Tough, sturdy, and resistant to heat.",
        statDeltas = mapOf("con" to 2),
        maxVitalDeltas = mapOf("hp" to 5),
        resistDeltas = mapOf("fire" to 10),
      )
    )
    r.addTrait(
      PluginTrait(
        name = "warrior",
        label = "Warrior",
        doc = "Master of martial prowess.",
        statDeltas = mapOf("str" to 2),
        maxVitalDeltas = mapOf("hp" to 10),
      )
    )
    r.addTrait(
      PluginTrait(
        name = "mage",
        label = "Mage",
        doc = "Wielder of arcane mysteries.",
        statDeltas = mapOf("int" to 2),
        maxVitalDeltas = mapOf("mana" to 20),
      )
    )
    r.addTrait(
      PluginTrait(
        name = "cleric",
        label = "Cleric",
        doc = "Channeler of divine grace.",
        statDeltas = mapOf("wis" to 2),
        maxVitalDeltas = mapOf("mana" to 10),
        modifiers =
          mapOf(
            PluginModifierPoint.REGEN to
              PluginModifier { _, base ->
                @Suppress("UNCHECKED_CAST") val map = (base as Map<String, Long>).toMutableMap()
                map["mana"] = (map["mana"] ?: 0L) + 1L
                map
              }
          ),
      )
    )

    // ---- Character Creation -----------------------------------------------------------------
    r.setCreation(
      object : PluginCreation {
        override val choices =
          listOf(
            PluginChoice("race", "Race", RACE_OPTIONS),
            PluginChoice("class", "Class", CLASS_OPTIONS),
          )

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

        override fun created(world: PluginWorld, actor: PluginActor, newCharacter: Boolean) {
          if (newCharacter) {
            val skill =
              when (actor.choices["class"]) {
                "mage" -> "//skills:magic_missile"
                "cleric" -> "//skills:minor_heal"
                else -> null
              }
            if (skill != null) {
              try {
                world.setSkill(actor, skill, 25)
              } catch (_: IllegalArgumentException) {
                // Skill not defined in world
              }
            }
          }
        }
      }
    )

    // ---- Restrictions -----------------------------------------------------------------------
    r.addRestriction(PluginRestrictionPoint.WEAR, "Mages cannot wear heavy armor.") { call ->
      val actor = call.actor
      val item = call.item
      if (actor.hasTrait("mage") && item != null && "heavy_armor" in item.tags) {
        "Your spellcraft can't work through that much metal."
      } else null
    }

    r.addRestriction(
      PluginRestrictionPoint.CAST,
      "Arcane skills require the mage trait; divine skills require the cleric trait.",
    ) { call ->
      val actor = call.actor
      val skill = call.skill
      if (skill != null) {
        if ("arcane" in skill.tags && !actor.hasTrait("mage")) {
          "You don't know how to call on that power."
        } else if ("divine" in skill.tags && !actor.hasTrait("cleric")) {
          "You don't know how to call on that power."
        } else null
      } else null
    }

    r.addRestriction(
      PluginRestrictionPoint.ENTER,
      "Guild rooms admit only actors with matching class trait or staff.",
    ) { call ->
      val actor = call.actor
      val room = call.room ?: return@addRestriction null
      if (
        actor.role == PluginRole.BUILDER ||
          actor.role == PluginRole.INSTRUCTOR ||
          actor.role == PluginRole.ADMIN
      ) {
        return@addRestriction null
      }
      val classes = listOf("warrior" to "Warrior", "mage" to "Mage", "cleric" to "Cleric")
      for ((c, label) in classes) {
        if ("guild_$c" in room.tags && !actor.hasTrait(c)) {
          return@addRestriction "Only ${label}s may enter."
        }
      }
      null
    }

    r.addRestriction(
      PluginRestrictionPoint.LEARN,
      "Arcane skills require the mage trait; divine skills require the cleric trait.",
    ) { call ->
      val actor = call.actor
      val skill = call.skill
      if (skill != null) {
        if ("arcane" in skill.tags && !actor.hasTrait("mage")) {
          "You don't know how to call on that power."
        } else if ("divine" in skill.tags && !actor.hasTrait("cleric")) {
          "You don't know how to call on that power."
        } else null
      } else null
    }

    // ---- Builtin & Command ------------------------------------------------------------------
    r.addBuiltin(
      name = "choose",
      doc = "Sets or changes a character's choice. Returns the refusal message or None.",
      params =
        listOf(
          PluginParam("actor", "actor", "The actor to modify."),
          PluginParam("choice", "str", "The choice id to set."),
          PluginParam(
            "option",
            "str",
            "The option id, or None to clear.",
            required = false,
            defaultValue = null,
          ),
        ),
      returnType = "str",
    ) { world, pos, _ ->
      val actor =
        pos[0] as? PluginActor
          ?: throw IllegalArgumentException("heritage.choose(actor): expected an actor")
      val choice =
        pos[1] as? String
          ?: throw IllegalArgumentException("heritage.choose(choice): expected a string")
      val option = if (pos.size > 2) pos[2] as? String else null
      world.setChoices(actor, mapOf(choice to option))
    }

    r.addCommand("heritage", PluginLane.FAST, PluginRole.PLAYER, "Displays your race and class.") {
      call ->
      val actor = call.actor
      val race = actor.choices["race"]
      val cls = actor.choices["class"]
      val lines = mutableListOf<String>()
      if (race != null) {
        val opt = RACE_OPTIONS.find { it.id == race }
        lines += "Race: ${opt?.label ?: race} - ${opt?.summary ?: ""}"
      }
      if (cls != null) {
        val opt = CLASS_OPTIONS.find { it.id == cls }
        lines += "Class: ${opt?.label ?: cls} - ${opt?.summary ?: ""}"
      }
      if (lines.isEmpty()) {
        call.world.send(actor, "You have not chosen a race or class.")
      } else {
        call.world.send(actor, lines.joinToString("\n"))
      }
    }
  }

  companion object {
    val RACE_OPTIONS =
      listOf(
        PluginOption(
          "human",
          "Human",
          "Adaptable and well-rounded.",
          help = "Humans receive +1 to all attributes except armor.",
          traits = listOf("human"),
        ),
        PluginOption(
          "elf",
          "Elf",
          "Graceful and attuned to magic.",
          help = "Elves receive +2 dexterity, -1 constitution, and +10 mana.",
          traits = listOf("elf"),
        ),
        PluginOption(
          "dwarf",
          "Dwarf",
          "Sturdy folk with deep constitution.",
          help = "Dwarves receive +2 constitution, +5 hp, and +10 fire resistance.",
          traits = listOf("dwarf"),
        ),
      )

    val CLASS_OPTIONS =
      listOf(
        PluginOption(
          "warrior",
          "Warrior",
          "Masters of weapons and armor.",
          help = "Warriors receive +2 strength and +10 hp.",
          traits = listOf("warrior"),
        ),
        PluginOption(
          "mage",
          "Mage",
          "Scholars of arcane destruction.",
          help = "Mages receive +2 intelligence and +20 mana.",
          traits = listOf("mage"),
        ),
        PluginOption(
          "cleric",
          "Cleric",
          "Holy servants of divine recovery.",
          help = "Clerics receive +2 wisdom, +10 mana, and bonus mana regeneration.",
          traits = listOf("cleric"),
        ),
      )
  }
}
