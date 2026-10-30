package gups.script

import gups.core.world.Feature
import gups.core.world.Features

/**
 * Which feature owns an entry of the script API, by the entry's `symbol` in `api.json`. Entries
 * that no feature owns belong to the kernel. A feature that is off refuses its entries (see
 * [refusal]) and the docs of a world leave them out.
 */
object FeatureOwnership {
  private val exact: Map<String, Feature> = buildMap {
    fun owns(feature: Feature, vararg symbols: String) = symbols.forEach { put(it, feature) }
    owns(Feature.SKILLS, "mob.skills", "ctx.act_cast", "ctx.skill")
    owns(Feature.SKILLS, "hook:before_cast", "hook:on_cast_start", "hook:on_cast")
    owns(Feature.SKILLS, "rules:cast_allowed", "rules:interrupt_check", "modifier:cast_allowed")
    owns(Feature.SKILLS, "view:actor.skills", "view:actor.casting", "view:actor.is_casting")
    owns(Feature.LEVELING, "mob.level", "aggressive.unless_level_above")
    owns(Feature.LEVELING, "view:actor.level", "view:actor.xp")
    owns(Feature.LEVELING, "rules:xp_for_kill", "rules:xp_to_level", "rules:on_level_up")
    owns(Feature.COMBAT, "hook:before_attack", "hook:on_combat_tick")
    owns(
      Feature.COMBAT,
      "rules:to_hit",
      "rules:damage",
      "rules:initiative",
      "rules:flee_chance",
      "rules:flee_fail_lag",
    )
    owns(Feature.COMBAT, "modifier:to_hit", "modifier:damage", "modifier:initiative")
    owns(Feature.COMBAT, "view:actor.fighting")
    owns(Feature.TRACK, "room.size", "exit.entry_index", "spawn.at_index", "item.range")
    owns(Feature.TRACK, "SIZE")
    owns(Feature.TRACK, "ctx.act_advance", "ctx.act_retreat")
    owns(Feature.TRACK, "ctx.teleport.index", "ctx.spawn.index", "ctx.move_item.index")
    owns(Feature.TRACK, "rules:retreat_check", "rules:get_lag", "rules_ctx.band")
    owns(Feature.TRACK, "rules:advance_lag", "rules:retreat_lag", "rules:retreat_fail_lag")
    owns(Feature.TRACK, "view:actor.track_index", "view:actor.band_of")
    owns(Feature.TRACK, "view:room.size", "view:room.track_size", "view:exit.entry_index")
    owns(Feature.TRACK, "view:item.range", "view:location.index")
  }

  /** Entries whose parameters and fields are owned along with them. */
  private val subtrees: Map<String, Feature> = buildMap {
    fun owns(feature: Feature, vararg symbols: String) = symbols.forEach { put(it, feature) }
    owns(Feature.SKILLS, "skill", "view:skill")
    owns(Feature.SKILLS, "damage", "heal", "apply_affect", "dispel", "stun", "summon", "sequence")
    owns(Feature.COMBAT, "aggressive", "assist", "flee")
    owns(Feature.COMBAT, "ctx.start_combat", "ctx.stop_combat", "ctx.act_attack")
    owns(Feature.COMBAT, "ctx.enemies_near", "ctx.allies_near")
    owns(Feature.TRACK, "knockback", "pull")
  }

  /** The feature that owns the entry named by [symbol] in `api.json`, or null for the kernel. */
  fun of(symbol: String): Feature? {
    exact[symbol]?.let {
      return it
    }
    var path = symbol
    while (true) {
      subtrees[path]?.let {
        return it
      }
      val dot = path.lastIndexOf('.')
      if (dot < 0) return null
      path = path.substring(0, dot)
    }
  }

  /** The error for using [name], which [feature] owns, when it is off. */
  internal fun refusal(name: String, feature: Feature) = "$name needs the ${feature.id} feature"

  /**
   * The refusal for [symbol] used as [name] in a world with [features], or null if it is allowed.
   */
  internal fun refused(symbol: String, name: String, features: Features): String? =
    of(symbol)?.takeIf { it !in features }?.let { refusal(name, it) }
}
