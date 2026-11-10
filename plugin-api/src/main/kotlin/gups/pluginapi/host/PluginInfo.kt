package gups.pluginapi.host

import gups.pluginapi.PluginLane
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRole

/** What `gups docs` shows about one plugin; `:cli` maps it to its `PluginDocSpec`. */
data class PluginInfo(
  val id: String,
  val doc: String,
  val builtins: List<PluginMemberInfo>,
  val effects: List<PluginMemberInfo>,
  val commands: List<PluginCommandInfo>,
  val gmcp: List<PluginGmcpInfo>,
  val hooks: List<PluginHookInfo> = emptyList(),
  val traits: List<PluginTraitInfo> = emptyList(),
  val restrictions: List<PluginRestrictionInfo> = emptyList(),
  val choices: List<PluginChoiceInfo> = emptyList(),
  val containmentFilters: List<PluginFilterInfo> = emptyList(),
  val ownedBehaviors: List<PluginOwnedBehaviorsInfo> = emptyList(),
  val targetOrders: List<PluginTargetOrderInfo> = emptyList(),
  val chainThinks: List<PluginChainThinkInfo> = emptyList(),
  val dispositionModifiers: List<PluginDispositionModifierInfo> = emptyList(),
  val factions: List<PluginFactionAdjusterInfo> = emptyList(),
  val steps: List<PluginStepInfo> = emptyList(),
  val rulesExports: List<PluginRulesExportInfo> = emptyList(),
)

data class PluginRulesExportInfo(val name: String, val doc: String, val type: String)

data class PluginStepInfo(val id: String, val title: String)

data class PluginFilterInfo(val doc: String)

data class PluginOwnedBehaviorsInfo(val doc: String)

data class PluginTargetOrderInfo(val doc: String)

data class PluginChainThinkInfo(val doc: String)

data class PluginDispositionModifierInfo(val doc: String)

data class PluginFactionAdjusterInfo(val doc: String)

data class PluginTraitInfo(
  val name: String,
  val label: String,
  val doc: String,
  val statDeltas: Map<String, Int> = emptyMap(),
  val maxVitalDeltas: Map<String, Int> = emptyMap(),
  val resistDeltas: Map<String, Int> = emptyMap(),
  val flags: Set<String> = emptySet(),
  val modifiers: Set<String> = emptySet(),
  val senseDeltas: Map<String, Int> = emptyMap(),
)

data class PluginRestrictionInfo(val point: String, val doc: String)

data class PluginOptionInfo(
  val id: String,
  val label: String,
  val summary: String,
  val help: String,
  val traits: List<String>,
)

data class PluginChoiceInfo(
  val id: String,
  val title: String,
  val prompt: String,
  val options: List<PluginOptionInfo>,
)

data class PluginMemberInfo(
  val name: String,
  val doc: String,
  val params: List<PluginParam>,
  val returnType: String = "any",
)

data class PluginCommandInfo(
  val name: String,
  val lane: PluginLane,
  val role: PluginRole,
  val doc: String,
  val category: String? = null,
)

data class PluginGmcpInfo(val pkg: String, val doc: String, val kind: String)

data class PluginHookInfo(val name: String, val doc: String)
