package gups.pluginapi

import gups.core.Label
import gups.pluginapi.host.PluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginChainThinkTest {
  // A wander that always moves, once its think cycle comes due, so each completed move asks the
  // chain adjuster once and a handler that keeps the answer true chains it through several moves.
  // A fast pace lets a chained mob take several steps in a single pulse.
  private val chainFiles =
    ZONE +
      mapOf(
        "rules/package.star" to "package(api_version = 1, kind = \"rules\")",
        "rules/movement.star" to
          """
          def move_pace(ctx):
              return 25
          """,
        "zones/z/mobs.star" to
          """
          mob("wanderer", name = "a wanderer", behaviors = [wander(chance = 100)], think_every = 1)
          """,
      )

  @Test
  fun `a handler that always returns true makes a wander mob chain`() {
    var calls = 0
    val plugin =
      object : GupsPlugin {
        override val id = "chainall"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addChainThink("Chain every action") { call ->
            calls += 1
            assertTrue(!call.chained, "wander's default is not to chain")
            true
          }
        }
      }

    val w = world(files = chainFiles, plugins = PluginHost.of(plugin))
    w.spawnMob(Label("//zones/z:wanderer"), w.room(Label("//zones/z:a")))
    w.runPulse()
    // An unchained wander asks once per move; a chained one thinks again after each arrival and
    // keeps moving until the per-pulse move cap, so the adjuster is asked several times.
    assertTrue(calls >= 4, "expected several chain asks in one pulse, got $calls")
  }

  @Test
  fun `a handler returning false keeps the default`() {
    var calls = 0
    var sawChained: Boolean? = null
    val plugin =
      object : GupsPlugin {
        override val id = "chainstop"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addChainThink("Never chain") { call ->
            calls += 1
            sawChained = call.chained
            false
          }
        }
      }

    val w = world(files = chainFiles, plugins = PluginHost.of(plugin))
    w.spawnMob(Label("//zones/z:wanderer"), w.room(Label("//zones/z:a")))
    repeat(5) { w.runPulse() }
    // No chaining: the wander moves once per pulse, so one chain ask per pulse, five times.
    assertEquals(5, calls)
    // The handler saw the rules default for wander, which is not to chain.
    assertEquals(false, sawChained)
  }

  @Test
  fun `a throwing handler records a plugin diagnostic and keeps the answer`() {
    val plugin =
      object : GupsPlugin {
        override val id = "chainthrow"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addChainThink("Explodes") { error("boom") }
        }
      }

    val w = world(files = chainFiles, plugins = PluginHost.of(plugin))
    val mob = w.spawnMob(Label("//zones/z:wanderer"), w.room(Label("//zones/z:a")))
    val start = mob.room.label
    w.runPulse()
    // The handler threw; the engine records a plugin diagnostic and keeps the answer so far.
    assertTrue(
      w.diagnostics.any { it.kind == "plugin" && "chainthrow" in it.message },
      "expected a plugin diagnostic for the throwing handler",
    )
    // And the world still ran: the wander moved on to the neighbouring room.
    assertTrue(mob.room.label != start, "the mob should have moved")
  }

  @Test
  fun `two handlers run in plugin-id order and the second sees the firsts answer`() {
    var secondSaw: Boolean? = null
    val first =
      object : GupsPlugin {
        override val id = "aaa"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addChainThink("Force chain") { true }
        }
      }

    val second =
      object : GupsPlugin {
        override val id = "zzz"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addChainThink("Observe") { call ->
            secondSaw = call.chained
            call.chained
          }
        }
      }

    // Pass in reverse ID order to verify plugin-id ordering.
    val w = world(files = chainFiles, plugins = PluginHost.of(second, first))
    w.spawnMob(Label("//zones/z:wanderer"), w.room(Label("//zones/z:a")))
    w.runPulse()
    assertEquals(true, secondSaw, "the second handler should see the first's forced chain")
  }
}
