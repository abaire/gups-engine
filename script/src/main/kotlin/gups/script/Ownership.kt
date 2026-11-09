package gups.script

import gups.core.world.Feature
import gups.core.world.FeatureRequirement
import gups.core.world.Features

/**
 * Which feature owns an entry of the script API, by the entry's `symbol` in `api.json`. Entries
 * that no feature owns belong to the kernel. A feature that is off refuses its entries (see
 * [refusal]) and the docs of a world leave them out.
 */
object FeatureOwnership {
  private val exact: Map<String, FeatureRequirement> = buildMap {
    fun owns(req: FeatureRequirement, vararg symbols: String) = symbols.forEach { put(it, req) }
    fun owns(feature: Feature, vararg symbols: String) =
      owns(FeatureRequirement.of(feature), *symbols)
    owns(Feature.SKILLS, "mob.skills", "ctx.act_cast", "ctx.skill", "ctx.power")
    owns(
      Feature.SKILLS,
      "ctx.set_skill",
      "ctx.remove_skill",
      "ctx.skill_info",
      "ctx.report_skill_use",
    )
    owns(Feature.SKILLS, "w.set_skill", "w.remove_skill")
    owns(
      Feature.SKILLS,
      "hook:before_cast",
      "hook:on_cast_start",
      "hook:on_cast",
      "hook:on_skill_use",
    )
    owns(Feature.SKILLS, "ctx.outcome")
    owns(
      Feature.SKILLS,
      "rules:cast_allowed",
      "rules:interrupt_check",
      "rules:skill_check",
      "rules:skill_power",
      "rules:recognizes_skill",
      "modifier:cast_allowed",
      "modifier:skill_check",
      "modifier:skill_power",
    )
    owns(Feature.SKILLS, "view:actor.skills", "view:actor.casting", "view:actor.is_casting")
    owns(Feature.LEVELING, "module.level_mode", "mob.level", "aggressive.unless_level_above")
    owns(Feature.LEVELING, "aggressive.unless_level_below")
    owns(Feature.LEVELING, "hook:on_level_change", "ctx.old_level", "ctx.new_level")
    owns(Feature.LEVELING, "view:actor.level", "view:actor.xp", "view:actor.pending_levels")
    owns(
      Feature.LEVELING,
      "rules:xp_for_kill",
      "rules:xp_share",
      "rules:xp_to_level",
      "rules:on_level_up",
    )
    owns(Feature.LEVELING, "ctx.award_xp", "ctx.take_xp", "ctx.level_up", "ctx.level_down")
    owns(Feature.LEVELING, "w.award_xp", "w.take_xp", "w.level_up", "w.level_down")
    owns(
      FeatureRequirement.anyOf(Feature.COMBAT, Feature.SKILLS),
      "rules:to_hit",
      "modifier:to_hit",
      "modifier:defend_hit",
    )
    owns(Feature.COMBAT, "hook:before_attack", "hook:on_combat_tick", "hook:on_disengage")
    owns(
      Feature.COMBAT,
      "rules:damage",
      "rules:initiative",
      "rules:flee_chance",
      "rules:flee_fail_lag",
      "rules:target_order",
      "aggressive.prefer",
      "assist.prefer",
      "rules_ctx.behavior",
      "rules_ctx.prefer",
      "rules_ctx.candidates",
      "rules_ctx.current",
      "rules_ctx.current_since",
      "rules_ctx.dispositions",
      "rules_ctx.ally",
      "rules_ctx.memories",
    )
    owns(Feature.COMBAT, "modifier:initiative", "modifier:flee_fail_lag")
    owns(Feature.COMBAT, "view:actor.fighting", "view:actor.primary_target")
    owns(Feature.COMBAT, "module.memory_pulses", "mob.memory_pulses")
    owns(
      Feature.COMBAT,
      "item.procs",
      "item_affect.procs",
      "apply_affect.procs",
      "ctx.apply_affect.procs",
    )
    owns(Feature.TRACK, "room.size", "exit.entry_index", "spawn.at_index", "item.range")
    owns(Feature.TRACK, "SIZE")
    owns(Feature.TRACK, "ctx.act_advance", "ctx.act_retreat")
    owns(Feature.TRACK, "ctx.teleport.index", "ctx.spawn.index", "ctx.move_item.index")
    owns(Feature.TRACK, "rules:retreat_check", "rules:get_lag", "rules_ctx.band")
    owns(Feature.TRACK, "rules:advance_lag", "rules:retreat_lag", "rules:retreat_fail_lag")
    owns(Feature.TRACK, "modifier:advance_lag", "modifier:retreat_lag", "modifier:retreat_fail_lag")
    owns(Feature.TRACK, "view:actor.track_index", "view:actor.band_of")
    owns(Feature.TRACK, "view:room.size", "view:room.track_size", "view:exit.entry_index")
    owns(Feature.TRACK, "view:item.range", "view:location.index")
    owns(
      Feature.STANDING,
      "mob.factions",
      "module.standing_history_pulses",
      "rules:factions",
      "rules:disposition",
      "rules:standing_change",
      "rules_ctx.disposition_parts",
      "rules_ctx.amount",
      "rules_ctx.helped",
      "rules_ctx.pet",
      "rules_ctx.standing",
      "rules_ctx.factions_of",
      "rules_ctx.standing_history",
      "aggressive.disposition_below",
      "retarget.disposition_below",
      "pursue.disposition_below",
      "assist.disposition_above",
      "guard.disposition_above",
      "follow.disposition_above",
    )
  }

  /** Entries whose parameters and fields are owned along with them. */
  private val subtrees: Map<String, FeatureRequirement> = buildMap {
    fun owns(req: FeatureRequirement, vararg symbols: String) = symbols.forEach { put(it, req) }
    fun owns(feature: Feature, vararg symbols: String) =
      owns(FeatureRequirement.of(feature), *symbols)
    owns(Feature.SKILLS, "skill", "view:skill")
    owns(Feature.COMBAT, "aggressive", "assist", "retarget", "flee", "defend")
    owns(Feature.COMBAT, "ctx.start_combat", "ctx.stop_combat", "ctx.act_attack")
    owns(Feature.COMBAT, "ctx.enemies_near", "ctx.allies_near")
    owns(Feature.COMBAT, "ctx.memories", "ctx.remember", "ctx.forget")
    owns(Feature.COMBAT, "proc", "outcome")
    owns(Feature.TRACK, "knockback", "pull")
    owns(
      Feature.STANDING,
      "faction",
      "standing_delta",
      "ctx.disposition",
      "ctx.standing",
      "ctx.factions_of",
      "ctx.standing_history",
      "ctx.standing_event",
      "ctx.adjust_standing",
      "ctx.clear_standing",
    )
  }

  /** The requirement of the entry named by [symbol] in `api.json`, or null for the kernel. */
  fun of(symbol: String): FeatureRequirement? {
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

  /** The error for using [name], which needs [req], when it isn't met. */
  internal fun refusal(name: String, req: FeatureRequirement): String =
    "$name needs the ${req.text} ${req.noun}"

  /** Kept for the existing single-feature call sites. */
  internal fun refusal(name: String, feature: Feature): String =
    refusal(name, FeatureRequirement.of(feature))

  /**
   * The refusal for [symbol] used as [name] in a world with [features], or null if it is allowed.
   */
  fun refused(symbol: String, name: String, features: Features): String? =
    of(symbol)?.takeIf { !it.satisfiedBy(features) }?.let { refusal(name, it) }
}
