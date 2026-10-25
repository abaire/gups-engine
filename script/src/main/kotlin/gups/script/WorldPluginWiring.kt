package gups.script

import gups.core.world.World

/** What the script layer needs from the world's plugins; `:plugin-api` implements it (M7.4). */
interface WorldPluginWiring {
    /** Plugin namespaces by id, predeclared in every package and test file. */
    val globals: Map<String, Any>

    /** Registers plugin commands, effects and GMCP producers. Call before StarlarkHost.install. */
    fun install(world: World)

    /** Runs the onWorldStarted callbacks. Call after World.fireOnLoad. */
    fun started(world: World)

    companion object {
        val NONE: WorldPluginWiring = object : WorldPluginWiring {
            override val globals: Map<String, Any> = emptyMap()

            override fun install(world: World) = Unit

            override fun started(world: World) = Unit
        }
    }
}
