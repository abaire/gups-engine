package gups.pluginapi

import net.starlark.java.eval.StarlarkValue

// The plugin interface (SPEC §4.15). Unstable: no compatibility promises.

object PluginApi {
  /**
   * Changes whenever this interface or the facade changes; plugins built for another version are
   * refused.
   */
  const val VERSION: Int = 1
}

/** A plugin, found through `ServiceLoader` in `plugins/<id>.jar`. */
interface GupsPlugin {
  /** `[a-z][a-z0-9_]*`, unique, and the id `MODULE.gups` pins the jar under. */
  val id: String

  /** Must equal [PluginApi.VERSION]. */
  val pluginApiVersion: Int

  val doc: String
    get() = ""

  /**
   * Registers everything the plugin adds. Registration is atomic: if it throws, the plugin adds
   * nothing.
   */
  fun register(r: PluginRegistry)
}

interface PluginRegistry {
  /** Installs [value] as `<id>.<name>` as is. */
  fun addBuiltin(name: String, value: StarlarkValue, doc: String)

  /**
   * A builtin `<id>.<name>(...)` whose arguments are bound to [params] and converted to plain
   * values.
   */
  fun addBuiltin(
    name: String,
    doc: String,
    params: List<PluginParam> = emptyList(),
    returnType: String = "any",
    call: (world: PluginWorld, positional: List<Any?>, named: Map<String, Any?>) -> Any?,
  )

  fun addCommand(
    name: String,
    lane: PluginLane,
    role: PluginRole = PluginRole.PLAYER,
    doc: String,
    run: (PluginCommandCall) -> Unit,
  )

  /** An effect primitive `<id>.<name>(...)` for `skill(effect = …)` and `sequence([...])`. */
  fun addEffectPrimitive(name: String, doc: String, params: List<PluginParam>, apply: PluginEffect)

  /** A GMCP state package (at least two dot-separated parts), sent in phase 7 when it changes. */
  fun addGmcp(pkg: String, doc: String, produce: (PluginWorld, PluginActor) -> Any?)

  fun onWorldStarted(fn: (PluginWorld) -> Unit)
}

/** Mirrors core's command lanes. */
enum class PluginLane {
  FAST,
  TICK,
}

/** Mirrors core's roles. */
enum class PluginRole {
  PLAYER,
  BUILDER,
  INSTRUCTOR,
  ADMIN,
}

data class PluginParam(
  val name: String,
  /** Documentation only, for example "int" or "room". */
  val type: String,
  val doc: String,
  val required: Boolean = true,
  val defaultValue: Any? = null,
)

data class PluginCommandCall(val world: PluginWorld, val actor: PluginActor, val args: String)

data class PluginEffectCall(
  val world: PluginWorld,
  val caster: PluginActor,
  /** Null for `target = "none"` skills. */
  val target: PluginActor?,
  val args: Map<String, Any?>,
)

fun interface PluginEffect {
  fun apply(call: PluginEffectCall)
}
