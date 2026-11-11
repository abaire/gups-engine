package gups.sample.heritage

import gups.pluginapi.PluginActor
import gups.pluginapi.PluginCommandCall
import gups.pluginapi.PluginCreation
import gups.pluginapi.PluginGmcpCall
import gups.pluginapi.PluginLane
import gups.pluginapi.PluginParam
import gups.pluginapi.PluginRegistry
import gups.pluginapi.PluginRestrictionCall
import gups.pluginapi.PluginRestrictionPoint
import gups.pluginapi.PluginRole
import gups.pluginapi.PluginTrait
import gups.pluginapi.PluginWorld

class FakeRegistry : PluginRegistry {
  val traits = mutableMapOf<String, PluginTrait>()
  private var _creation: PluginCreation? = null
  val creation: PluginCreation?
    get() = _creation

  val restrictions =
    mutableMapOf<
      PluginRestrictionPoint,
      MutableList<Pair<String, (PluginRestrictionCall) -> String?>>,
    >()
  val builtins = mutableMapOf<String, (PluginWorld, List<Any?>, Map<String, Any?>) -> Any?>()
  val commands = mutableMapOf<String, (PluginCommandCall) -> Unit>()

  override fun addTrait(trait: PluginTrait) {
    traits[trait.name] = trait
  }

  override fun setCreation(creation: PluginCreation) {
    this._creation = creation
  }

  override fun addRestriction(
    point: PluginRestrictionPoint,
    doc: String,
    check: (PluginRestrictionCall) -> String?,
  ) {
    restrictions.getOrPut(point) { mutableListOf() }.add(doc to check)
  }

  override fun addContainmentFilter(
    doc: String,
    blocks: (gups.pluginapi.PluginContainmentCall) -> Boolean,
  ) = TODO()

  override fun addTargetOrder(
    doc: String,
    choose: (gups.pluginapi.PluginTargetOrderCall) -> gups.pluginapi.PluginTargetChoice,
  ) = TODO()

  override fun addChainThink(
    doc: String,
    decide: (gups.pluginapi.PluginChainThinkCall) -> Boolean,
  ) = TODO()

  override fun addOwnedBehaviors(
    doc: String,
    adjust: (gups.pluginapi.PluginOwnedBehaviorsCall) -> List<Map<String, Any?>>,
  ) = TODO()

  override fun addDispositionModifier(
    doc: String,
    adjust: (gups.pluginapi.PluginDispositionCall) -> Int,
  ) = TODO()

  override fun addFactions(
    doc: String,
    adjust: (gups.pluginapi.PluginFactionsCall) -> List<String>,
  ) = TODO()

  override fun addHook(name: String, doc: String, handle: (gups.pluginapi.PluginHookCall) -> Any?) =
    TODO()

  override fun addBuiltin(
    name: String,
    doc: String,
    params: List<PluginParam>,
    returnType: String,
    call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
  ) {
    builtins[name] = call
  }

  override fun addCommand(
    name: String,
    lane: PluginLane,
    role: PluginRole,
    doc: String,
    run: (PluginCommandCall) -> Unit,
  ) {
    commands[name] = run
  }

  override fun addBuiltin(name: String, value: net.starlark.java.eval.StarlarkValue, doc: String) =
    TODO()

  override fun addEffectPrimitive(
    name: String,
    doc: String,
    params: List<PluginParam>,
    apply: gups.pluginapi.PluginEffect,
  ) = TODO()

  override fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?) =
    TODO()

  override fun addGmcpEvent(pkg: String, doc: String) = TODO()

  override fun onGmcp(pkg: String, doc: String, handle: (PluginGmcpCall) -> Unit) = TODO()

  override fun onWorldStarted(fn: (PluginWorld) -> Unit) = TODO()
}
