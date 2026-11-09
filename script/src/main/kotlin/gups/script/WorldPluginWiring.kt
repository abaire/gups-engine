package gups.script

import gups.core.world.World

/** What the script layer needs from the world's plugins; `:plugin-api` implements it. */
interface WorldPluginWiring {
  /** Plugin namespaces by id, predeclared in every package and test file. */
  val globals: Map<String, Any>

  /** Known trait names registered by plugins. */
  val traits: Set<String>
    get() = emptySet()

  /** Every GMCP package plugins registered: state, event and input. */
  val gmcpPackages: Set<String>
    get() = emptySet()

  /** Plugin-declared rules exports: name to `"int"` or `"bool"`. */
  val rulesExports: Map<String, String>
    get() = emptyMap()

  /** Registers plugin commands, effects and GMCP producers. Call before StarlarkHost.install. */
  fun install(world: World)

  /** Runs the onWorldStarted callbacks. Call after World.start. */
  fun started(world: World)

  companion object {
    val NONE: WorldPluginWiring =
      object : WorldPluginWiring {
        override val globals: Map<String, Any> = emptyMap()

        override fun install(world: World) = Unit

        override fun started(world: World) = Unit
      }
  }
}
