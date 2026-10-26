package gups.pluginapi

import gups.pluginapi.host.Facades
import net.starlark.java.eval.StarlarkThread

/**
 * Conversions for builtins installed with `addBuiltin(name, value, doc)`, which receive raw
 * Starlark values. Each accepts a facade value or a script view.
 */
object PluginViews {
  /**
   * The world of the hook running on [thread] (declare `useStarlarkThread = true`), or null outside
   * one.
   */
  fun world(thread: StarlarkThread): PluginWorld? = Facades.world(thread)

  fun room(value: Any?): PluginRoom? = Facades.room(value)

  fun actor(value: Any?): PluginActor? = Facades.actor(value)

  fun item(value: Any?): PluginItem? = Facades.item(value)
}
