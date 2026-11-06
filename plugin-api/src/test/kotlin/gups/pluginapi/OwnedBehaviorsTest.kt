package gups.pluginapi

import gups.core.Label
import gups.core.world.Defend
import gups.core.world.Heel
import gups.core.world.Scavenge
import gups.core.world.setOwner
import gups.pluginapi.host.PluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnedBehaviorsTest {
  @Test
  fun `a plugin raising heel priority to 80 changes the owned list`() {
    val plugin =
      object : GupsPlugin {
        override val id = "pack"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addOwnedBehaviors("Raises heel priority to 80") { call ->
            assertEquals("a wolf", call.mob.name)
            assertEquals("Alice", call.owner?.name)
            call.behaviors.map { desc ->
              if (desc["kind"] == "heel") {
                desc + mapOf("priority" to 80L)
              } else {
                desc
              }
            }
          }
        }
      }

    val w = world(plugins = PluginHost.of(plugin))
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val wolf = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))

    w.setOwner(wolf, alice)
    val owned = wolf.ownedBehaviors!!
    val heel = owned.filterIsInstance<Heel>().first()
    assertEquals(80, heel.priority)
    assertTrue(owned.any { it is Defend })
  }

  @Test
  fun `two plugins apply in plugin-id order`() {
    val one =
      object : GupsPlugin {
        override val id = "aaa"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addOwnedBehaviors("Adds scavenge") { call ->
            call.behaviors + mapOf("kind" to "scavenge", "priority" to 10L)
          }
        }
      }

    val two =
      object : GupsPlugin {
        override val id = "zzz"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addOwnedBehaviors("Raises scavenge priority") { call ->
            call.behaviors.map { desc ->
              if (desc["kind"] == "scavenge") {
                desc + mapOf("priority" to 25L)
              } else {
                desc
              }
            }
          }
        }
      }

    // Pass in reverse ID order to verify host sorts them by plugin-id
    val w = world(plugins = PluginHost.of(two, one))
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val wolf = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))

    w.setOwner(wolf, alice)
    val owned = wolf.ownedBehaviors!!
    val scavenge = owned.filterIsInstance<Scavenge>().first()
    assertEquals(25, scavenge.priority)
  }

  @Test
  fun `a throwing plugin leaves the list unchanged and records a diagnostic`() {
    val plugin =
      object : GupsPlugin {
        override val id = "boom"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addOwnedBehaviors("Throws an error") { error("kaboom") }
        }
      }

    val w = world(plugins = PluginHost.of(plugin))
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val wolf = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))

    w.setOwner(wolf, alice)
    val owned = wolf.ownedBehaviors!!
    assertTrue(owned.any { it is Heel })
    assertTrue(
      w.diagnostics.any {
        it.kind == "plugin" && it.message.contains("plugin 'boom': owned behaviors adjuster failed")
      },
      "Expected diagnostic for plugin error",
    )
  }

  @Test
  fun `an invalid descriptor returned by plugin leaves list unchanged and records a diagnostic`() {
    val plugin =
      object : GupsPlugin {
        override val id = "bad"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addOwnedBehaviors("Returns invalid descriptor") {
            listOf(mapOf("kind" to "invalid_kind"))
          }
        }
      }

    val w = world(plugins = PluginHost.of(plugin))
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val wolf = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))

    w.setOwner(wolf, alice)
    val owned = wolf.ownedBehaviors!!
    assertTrue(owned.any { it is Heel })
    assertTrue(
      w.diagnostics.any {
        it.kind == "plugin" && it.message.contains("plugin 'bad': owned behaviors adjuster failed")
      },
      "Expected diagnostic for invalid descriptor",
    )
  }
}
