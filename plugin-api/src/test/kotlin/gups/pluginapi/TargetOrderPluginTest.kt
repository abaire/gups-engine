package gups.pluginapi

import gups.core.Label
import gups.core.world.opponentsOf
import gups.pluginapi.host.PluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TargetOrderPluginTest {
  private val targetFiles =
    ZONE +
      mapOf(
        "zones/z/mobs.star" to
          """
          mob("orc_pref", name = "an orc", behaviors = [aggressive(prefer = "custom")])
          mob("orc_plain", name = "a plain orc", behaviors = [aggressive()])
          """
      )

  @Test
  fun `a handler that picks the last candidate overrides the rules Default`() {
    var capturedCall: PluginTargetOrderCall? = null
    val plugin =
      object : GupsPlugin {
        override val id = "picker"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addTargetOrder("Picks last candidate") { call ->
            capturedCall = call
            PluginTargetChoice.Pick(call.candidates.last())
          }
        }
      }

    val w = world(files = targetFiles, plugins = PluginHost.of(plugin))
    val glade = w.room(Label("//zones/z:a"))
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val bob = w.spawnPlayer("Bob", at = Label("//zones/z:a"))
    val orc = w.spawnMob(Label("//zones/z:orc_pref"), glade)

    repeat(2) { w.runPulse() }
    val call = assertNotNull(capturedCall)
    assertEquals("aggressive", call.behavior)
    assertEquals("custom", call.prefer)
    assertEquals(PluginTargetChoice.Default, call.choice)
    assertEquals(bob.id, orc.primaryTarget)
    assertEquals(
      listOf(alice, bob),
      call.candidates.map { it.id }.map { id -> if (id == alice.id.v) alice else bob },
    )
  }

  @Test
  fun `two handlers run in plugin-id order and the second sees the firsts choice`() {
    var secondSawChoice: PluginTargetChoice? = null
    val first =
      object : GupsPlugin {
        override val id = "aaa"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addTargetOrder("Picks first") { call ->
            PluginTargetChoice.Pick(call.candidates.first())
          }
        }
      }

    val second =
      object : GupsPlugin {
        override val id = "zzz"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addTargetOrder("Picks last") { call ->
            secondSawChoice = call.choice
            PluginTargetChoice.Pick(call.candidates.last())
          }
        }
      }

    // Pass in reverse ID order to verify plugin-id ordering
    val w = world(files = targetFiles, plugins = PluginHost.of(second, first))
    val glade = w.room(Label("//zones/z:a"))
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val bob = w.spawnPlayer("Bob", at = Label("//zones/z:a"))
    val orc = w.spawnMob(Label("//zones/z:orc_pref"), glade)

    repeat(2) { w.runPulse() }
    val saw = secondSawChoice as? PluginTargetChoice.Pick
    assertNotNull(saw)
    assertEquals(alice.id.v, saw.actor.id)
    assertEquals(bob.id, orc.primaryTarget)
  }

  @Test
  fun `Nobody stops the proposal`() {
    val plugin =
      object : GupsPlugin {
        override val id = "pacifist"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addTargetOrder("Attacks nobody") { PluginTargetChoice.Nobody }
        }
      }

    val w = world(files = targetFiles, plugins = PluginHost.of(plugin))
    val glade = w.room(Label("//zones/z:a"))
    w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val orc = w.spawnMob(Label("//zones/z:orc_pref"), glade)

    w.runPulse()
    assertTrue(w.opponentsOf(orc).isEmpty())
    assertNull(orc.primaryTarget)
  }

  @Test
  fun `a non-candidate pick or thrown exception records a plugin diagnostic and keeps previous choice`() {
    val first =
      object : GupsPlugin {
        override val id = "aaa_valid"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addTargetOrder("Picks last") { call -> PluginTargetChoice.Pick(call.candidates.last()) }
        }
      }

    val nonCandidate =
      object : GupsPlugin {
        override val id = "bbb_self"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addTargetOrder("Picks self (not a candidate)") { call ->
            PluginTargetChoice.Pick(call.mob)
          }
        }
      }

    val thrower =
      object : GupsPlugin {
        override val id = "ccc_throws"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addTargetOrder("Explodes") { error("boom") }
        }
      }

    val w = world(files = targetFiles, plugins = PluginHost.of(first, nonCandidate, thrower))
    val glade = w.room(Label("//zones/z:a"))
    w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val bob = w.spawnPlayer("Bob", at = Label("//zones/z:a"))
    val orc = w.spawnMob(Label("//zones/z:orc_pref"), glade)

    repeat(2) { w.runPulse() }
    // Previous choice (bob from aaa_valid) is preserved
    assertEquals(bob.id, orc.primaryTarget)
    assertTrue(w.diagnostics.any { it.kind == "plugin" && "bbb_self" in it.message })
    assertTrue(w.diagnostics.any { it.kind == "plugin" && "ccc_throws" in it.message })
  }

  @Test
  fun `handlers are not called without prefer`() {
    var called = false
    val plugin =
      object : GupsPlugin {
        override val id = "picker"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addTargetOrder("Should not run") {
            called = true
            PluginTargetChoice.Nobody
          }
        }
      }

    val w = world(files = targetFiles, plugins = PluginHost.of(plugin))
    val glade = w.room(Label("//zones/z:a"))
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val orc = w.spawnMob(Label("//zones/z:orc_plain"), glade)

    repeat(2) { w.runPulse() }
    assertFalse(called)
    assertEquals(alice.id, orc.primaryTarget)
  }
}
