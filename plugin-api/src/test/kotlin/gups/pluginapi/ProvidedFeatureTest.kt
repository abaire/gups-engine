package gups.pluginapi

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import gups.core.world.Feature
import gups.core.world.Features
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.handle
import gups.core.world.submit
import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.WorldFacade
import gups.pluginapi.testplugins.HungerProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ctx.has_feature` reports a feature a loaded plugin provides. */
class ProvidedFeatureTest {
  private val zone =
    mapOf(
      "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
      "zones/z/rooms.star" to
        """
        def _enter(ctx):
            ctx.send(ctx.actor, "hunger " + str(ctx.has_feature("hunger")))

        room("a", name = "Glade", desc = "A glade.", exits = [exit(DIR.north, ":b")])
        room("b", name = "Cave", desc = "A cave.", on_enter = _enter)
        """,
    )

  /** Moves [p] into the hook room and returns what it is told. */
  private fun output(w: World, p: Player): String {
    val id = SessionId(1)
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(id, sink))
    w.attach(id, p)
    w.submit(p, "north")
    w.runPulse()
    return sink.drain().trim()
  }

  @Test
  fun `has_feature answers False without a plugin and True with one that provides it`() {
    val bare = world(zone)
    val p = bare.spawnPlayer("Ann", Label("//zones/z:a"))
    val out = output(bare, p)
    assertTrue(out.endsWith("hunger False"), "a plain world has no 'hunger' feature: $out")

    val fed = world(zone, plugins = PluginHost.of(HungerProvider()))
    val q = fed.spawnPlayer("Ann", Label("//zones/z:a"))
    val fedOut = output(fed, q)
    assertTrue(fedOut.endsWith("hunger True"), "the provided feature is on: $fedOut")
  }

  @Test
  fun `PluginWorld features lists the enabled engine features and the provided ones`() {
    val w =
      world(
        zone,
        plugins = PluginHost.of(HungerProvider()),
        features = Features(setOf(Feature.COMBAT)),
      )
    assertEquals(setOf("combat", "hunger"), WorldFacade(w, "a") {}.features)
    assertEquals(setOf("combat"), WorldFacade(world(zone, features = w.features), "a") {}.features)
  }
}
