package gups.pluginapi

import gups.core.Label
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.disposition
import gups.core.world.factionsOf
import gups.core.world.opponentsOf
import gups.pluginapi.host.PluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandingPluginTest {
  private val standingFiles =
    ZONE +
      mapOf(
        "zones/z/factions.star" to
          """
          faction("heroes", standing = {":villains": -70})
          faction("villains", standing = {":heroes": -70})
          mob("villain", name = "a villain", factions = [":villains"],
              behaviors = [aggressive(disposition_below = 10)])
          """
      )

  @Test
  fun `a plugin adding +30 changes behavior thresholds outcome`() {
    var pluginCalled = false
    val plugin =
      object : GupsPlugin {
        override val id = "stand"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addDispositionModifier("Adds 30 to standing") { call ->
            pluginCalled = true
            call.disposition + 30
          }
        }
      }

    val w = world(files = standingFiles, plugins = PluginHost.of(plugin))
    val glade = w.room(Label("//zones/z:a"))
    val villain = w.spawnMob(Label("//zones/z:villain"), glade)
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))

    // Normally villain (disposition_below = 10) would attack alice (default standing 0 < 10).
    // But with plugin adding +30, standing is 30, which is not below 10.
    w.runPulse()
    assertTrue(pluginCalled)
    assertTrue(w.opponentsOf(villain).isEmpty())
  }

  @Test
  fun `two plugins apply in plugin-id order`() {
    val one =
      object : GupsPlugin {
        override val id = "aaa"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addDispositionModifier("Adds 10") { call -> call.disposition + 10 }
        }
      }

    val two =
      object : GupsPlugin {
        override val id = "zzz"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addDispositionModifier("Doubles") { call -> call.disposition * 2 }
        }
      }

    // Pass in reverse ID order
    val w = world(files = standingFiles, plugins = PluginHost.of(two, one))
    val glade = w.room(Label("//zones/z:a"))
    val villain = w.spawnMob(Label("//zones/z:villain"), glade)
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))

    // Base standing is 0. In ID order: (0 + 10) * 2 = 20.
    assertEquals(20, w.disposition(villain, alice))
  }

  @Test
  fun `a throwing plugin leaves the value unchanged with a diagnostic`() {
    val plugin =
      object : GupsPlugin {
        override val id = "broken"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addDispositionModifier("Explodes") { error("boom") }
        }
      }

    val w = world(files = standingFiles, plugins = PluginHost.of(plugin))
    val glade = w.room(Label("//zones/z:a"))
    val villain = w.spawnMob(Label("//zones/z:villain"), glade)
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))

    assertEquals(0, w.disposition(villain, alice))
    val diag = w.diagnostics.find { it.kind == "plugin" && "broken" in it.message }
    assertTrue(diag != null, "Diagnostic should be recorded")
    assertTrue(diag.message.contains("disposition modifier failed"))
  }

  @Test
  fun `a disposition modifier asking the disposition it adjusts gets a diagnostic`() {
    val plugin =
      object : GupsPlugin {
        override val id = "loop"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addDispositionModifier("Asks itself") { call ->
            call.world.disposition(call.holder, call.subject) + 10
          }
        }
      }

    val w = world(files = standingFiles, plugins = PluginHost.of(plugin))
    val glade = w.room(Label("//zones/z:a"))
    val villain = w.spawnMob(Label("//zones/z:villain"), glade)
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))

    assertEquals(0, w.disposition(villain, alice))
    val diag = w.diagnostics.find { it.kind == "plugin" && "loop" in it.message }
    assertTrue(diag != null, "Diagnostic should be recorded")
    assertTrue(diag.message.contains("still being worked out"))
  }

  @Test
  fun `addFactions adding a declared faction changes declared standing`() {
    val plugin =
      object : GupsPlugin {
        override val id = "fac"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addFactions("Adds heroes faction") { call -> call.factions + "//zones/z:heroes" }
        }
      }

    val w = world(files = standingFiles, plugins = PluginHost.of(plugin))
    val glade = w.room(Label("//zones/z:a"))
    val villain = w.spawnMob(Label("//zones/z:villain"), glade)
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))

    // Alice has heroes faction now, villain has villains faction.
    // villains faction opinion of heroes is -70.
    assertEquals(listOf("//zones/z:heroes"), w.factionsOf(alice).map { it.v })
    assertEquals(-70, w.disposition(villain, alice))
  }

  @Test
  fun `addFactions returning an unknown label drops it with a diagnostic`() {
    val plugin =
      object : GupsPlugin {
        override val id = "bogus"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addFactions("Adds unknown faction") { call ->
            call.factions + "//zones/z:unknown_faction"
          }
        }
      }

    val w = world(files = standingFiles, plugins = PluginHost.of(plugin))
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))

    assertEquals(emptyList(), w.factionsOf(alice))
    val diag = w.diagnostics.find { it.kind == "plugin" && "bogus" in it.message }
    assertTrue(diag != null, "Diagnostic should be recorded for unknown faction")
    assertTrue(diag.message.contains("unknown faction '//zones/z:unknown_faction'"))
  }

  @Test
  fun `PluginWorld facade standing, factionsOf, adjustStanding and raiseStandingEvent work`() {
    var capturedWorld: PluginWorld? = null
    val plugin =
      object : GupsPlugin {
        override val id = "facade_test"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.onWorldStarted { pw -> capturedWorld = pw }
        }
      }

    val customRules =
      standingFiles +
        mapOf(
          "rules/package.star" to gups.script.testing.TestWorlds.RULES_PACKAGE,
          "rules/combat.star" to gups.script.testing.TestWorlds.rules(),
          "rules/standing.star" to
            """
            def standing_change(ctx):
                if ctx.event == "quest_done":
                    return [standing_delta(ctx.actor, ctx.target, 50)]
                return []
            """,
        )

    val w = world(files = customRules, plugins = PluginHost.of(plugin))
    val pw = checkNotNull(capturedWorld)

    val glade = w.room(Label("//zones/z:a"))
    val villain = w.spawnMob(Label("//zones/z:villain"), glade)
    val alice = w.spawnPlayer("Alice", at = Label("//zones/z:a"))
    val villainFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "a villain" }!!
    val aliceFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "Alice" }!!

    // disposition and standing queries; declared opinions are not records
    assertEquals(0, pw.disposition(villainFacade, aliceFacade))
    assertEquals(0, pw.standing("//zones/z:villains", "//zones/z:heroes"))

    // factionsOf query
    assertEquals(listOf("//zones/z:villains"), pw.factionsOf(villainFacade))
    assertEquals(emptyList(), pw.factionsOf(aliceFacade))

    // adjustStanding
    pw.adjustStanding(villainFacade, aliceFacade, 25)
    assertEquals(25, pw.standing(villainFacade, aliceFacade))
    assertEquals(25, pw.disposition(villainFacade, aliceFacade))

    // adjustStanding faction to actor
    pw.adjustStanding("//zones/z:villains", aliceFacade, 15)
    assertEquals(15, pw.standing("//zones/z:villains", aliceFacade))
    assertEquals(25, pw.standing(villainFacade, aliceFacade))
    assertEquals(40, pw.disposition(villainFacade, aliceFacade))

    // raiseStandingEvent
    pw.raiseStandingEvent(villainFacade, aliceFacade, "quest_done", amount = 100)
    // 40 + 50 = 90
    assertEquals(90, pw.disposition(villainFacade, aliceFacade))
  }

  @Test
  fun `PluginWorld facade error validation and feature check`() {
    var capturedWorld: PluginWorld? = null
    val plugin =
      object : GupsPlugin {
        override val id = "error_test"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.onWorldStarted { pw -> capturedWorld = pw }
        }
      }

    val w = world(files = standingFiles, plugins = PluginHost.of(plugin))
    val pw = capturedWorld!!
    val glade = pw.room("//zones/z:a")!!
    val aliceFacade =
      glade.actors.find { it.name == "Alice" }
        ?: run {
          w.spawnPlayer("Alice", at = Label("//zones/z:a"))
          pw.room("//zones/z:a")!!.actors.find { it.name == "Alice" }!!
        }

    // Invalid holder / subject in standing()
    assertFailsWith<IllegalArgumentException> { pw.standing(123, aliceFacade) }
    assertFailsWith<IllegalArgumentException> { pw.standing(aliceFacade, 123) }

    // Invalid faction label
    assertFailsWith<IllegalArgumentException> { pw.standing("//zones/z:nonexistent", aliceFacade) }

    // Builtin event in raiseStandingEvent
    assertFailsWith<IllegalArgumentException> {
      pw.raiseStandingEvent(aliceFacade, aliceFacade, "attack")
    }

    // Invalid event name pattern
    assertFailsWith<IllegalArgumentException> {
      pw.raiseStandingEvent(aliceFacade, aliceFacade, "Invalid-Name")
    }

    // Negative amount
    assertFailsWith<IllegalArgumentException> {
      pw.raiseStandingEvent(aliceFacade, aliceFacade, "quest", amount = -5)
    }

    // A player has no personal records
    assertFailsWith<IllegalArgumentException> { pw.standing(aliceFacade, aliceFacade) }

    // Player personal standing in adjustStanding
    assertFailsWith<IllegalArgumentException> { pw.adjustStanding(aliceFacade, aliceFacade, 10) }

    // Personal standing with faction subject
    val villain = w.spawnMob(Label("//zones/z:villain"), w.room(Label("//zones/z:a")))
    val villainFacade = pw.room("//zones/z:a")!!.actors.find { it.name == "a villain" }!!
    assertFailsWith<IllegalArgumentException> {
      pw.adjustStanding(villainFacade, "//zones/z:heroes", 10)
    }

    // Now test with STANDING feature disabled
    val noStandingFeatures = Features(Features.ALL.enabled - Feature.STANDING)
    world(files = standingFiles, plugins = PluginHost.of(plugin), features = noStandingFeatures)
    val pwNoStanding = checkNotNull(capturedWorld)

    assertFailsWith<IllegalStateException> { pwNoStanding.standing(aliceFacade, aliceFacade) }
    assertFailsWith<IllegalStateException> { pwNoStanding.disposition(aliceFacade, aliceFacade) }
    assertFailsWith<IllegalStateException> { pwNoStanding.factionsOf(aliceFacade) }
    assertFailsWith<IllegalStateException> {
      pwNoStanding.raiseStandingEvent(aliceFacade, aliceFacade, "quest")
    }
    assertFailsWith<IllegalStateException> {
      pwNoStanding.adjustStanding("//zones/z:heroes", aliceFacade, 10)
    }
  }
}
