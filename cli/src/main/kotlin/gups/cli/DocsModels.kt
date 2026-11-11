package gups.cli

import gups.core.model.WorldSchema
import gups.core.world.CommandInfo
import gups.core.world.Features
import gups.script.MethodSpec

/** A command a plugin adds, for `plugins.html`. */
data class PluginCommandDocSpec(
  val name: String,
  val lane: String,
  val role: String,
  val doc: String,
)

/** A GMCP package a plugin adds, for `plugins.html`. */
data class PluginGmcpDocSpec(val pkg: String, val doc: String, val kind: String = "state")

/** A custom hook a plugin registers a handler for, for `plugins.html`. */
data class PluginHookDocSpec(val name: String, val doc: String)

/** An optional rules export a plugin declares, for `plugins.html`. */
data class PluginRulesExportDocSpec(val name: String, val doc: String, val type: String)

/** A GMCP package a script package declares, for `world.html`. */
data class ScriptGmcpDocSpec(val pkg: String, val doc: String, val declaredBy: String)

/** A trait a plugin adds, for `plugins.html`. */
data class PluginTraitDocSpec(
  val name: String,
  val label: String,
  val doc: String,
  val statDeltas: Map<String, Int> = emptyMap(),
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<String, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  val modifiers: Set<String> = emptySet(),
)

/** An option a plugin choice declares, for `plugins.html`. */
data class PluginOptionDocSpec(
  val id: String,
  val label: String,
  val summary: String,
  val help: String,
  val traits: List<String>,
)

/** A choice a plugin creation provider declares, for `plugins.html`. */
data class PluginChoiceDocSpec(
  val id: String,
  val title: String,
  val prompt: String,
  val options: List<PluginOptionDocSpec>,
)

/** A custom step a plugin creation provider declares, for `plugins.html`. */
data class PluginStepDocSpec(val id: String, val title: String)

/** A restriction a plugin registers, for `plugins.html`. */
data class PluginRestrictionDocSpec(val point: String, val doc: String)

/** A containment filter a plugin registers, for `plugins.html`. */
data class PluginFilterDocSpec(val doc: String)

/** An owned-behaviors adjuster a plugin registers, for `plugins.html`. */
data class PluginOwnedBehaviorsDocSpec(val doc: String)

/** A disposition modifier a plugin registers, for `plugins.html`. */
data class PluginDispositionModifierDocSpec(val doc: String)

/** A faction adjuster a plugin registers, for `plugins.html`. */
data class PluginFactionAdjusterDocSpec(val doc: String)

/** What `plugins.html` shows about one plugin. */
data class PluginDocSpec(
  val id: String,
  val doc: String = "",
  val builtins: List<MethodSpec> = emptyList(),
  val effects: List<MethodSpec> = emptyList(),
  val commands: List<PluginCommandDocSpec> = emptyList(),
  val gmcp: List<PluginGmcpDocSpec> = emptyList(),
  val hooks: List<PluginHookDocSpec> = emptyList(),
  val traits: List<PluginTraitDocSpec> = emptyList(),
  val restrictions: List<PluginRestrictionDocSpec> = emptyList(),
  val choices: List<PluginChoiceDocSpec> = emptyList(),
  val containmentFilters: List<PluginFilterDocSpec> = emptyList(),
  val ownedBehaviors: List<PluginOwnedBehaviorsDocSpec> = emptyList(),
  val dispositionModifiers: List<PluginDispositionModifierDocSpec> = emptyList(),
  val factions: List<PluginFactionAdjusterDocSpec> = emptyList(),
  val steps: List<PluginStepDocSpec> = emptyList(),
  val rulesExports: List<PluginRulesExportDocSpec> = emptyList(),
)

/** What `world.html` shows about one world, and what its pages leave out. */
data class WorldDocSpec(
  val schema: WorldSchema,
  val features: Features,
  val rename: Map<String, String>,
  val commands: List<CommandInfo>,
  /** `MESSAGES.gups` overrides by message id. */
  val messages: Map<String, String> = emptyMap(),
  /** GMCP packages the world's `lib` and `rules` packages declare. */
  val gmcp: List<ScriptGmcpDocSpec> = emptyList(),
)
