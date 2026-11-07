package gups.pluginapi

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.handle
import gups.pluginapi.host.PluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plugin periodic work: cadence, ordering, the actor list, failures. */
class PeriodicWorkTest {
  /** Five rooms in a line; the pit is four exits from the glade, beyond the bubble. */
  private val zone =
    mapOf(
      "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
      "zones/z/rooms.star" to
        """
        room("a", name = "Glade", desc = "A glade.", exits = [exit(DIR.north, ":b")])
        room("b", name = "Cave", desc = "A cave.", exits = [exit(DIR.south, ":a"), exit(DIR.north, ":c")])
        room("c", name = "Hollow", desc = "A hollow.", exits = [exit(DIR.south, ":b"), exit(DIR.north, ":d")])
        room("d", name = "Ruin", desc = "A ruin.", exits = [exit(DIR.south, ":c"), exit(DIR.north, ":e")])
        room("e", name = "Pit", desc = "A pit.", flags = ["dead_end_ok"])
        mob("wolf", name = "a wolf", desc = "A wolf.", keywords = ["wolf"])
        """,
    )

  private fun plugin(id: String, register: (PluginRegistry) -> Unit): GupsPlugin =
    object : GupsPlugin {
      override val id = id
      override val pluginApiVersion = PluginApi.VERSION

      override fun register(r: PluginRegistry) = register(r)
    }

  private fun World.connect(p: Player): CapturingSink {
    val id = SessionId(1)
    val sink = CapturingSink()
    handle(WorldControlEvent.Connect(id, sink))
    attach(id, p)
    return sink
  }

  @Test
  fun `every runs on its pulse multiples, in registration order, with plugins in id order`() {
    val events = mutableListOf<String>()
    val a =
      plugin("a") { r ->
        r.every(8, "First.") { events += "a1" }
        r.every(8, "Second.") { events += "a2" }
      }
    val b = plugin("b") { r -> r.every(8, "Third.") { events += "b" } }
    val w = world(zone, plugins = PluginHost.of(a, b))
    w.runPulses(24)
    assertEquals(listOf("a1", "a2", "b", "a1", "a2", "b", "a1", "a2", "b"), events)
  }

  @Test
  fun `actors are the simulated actors in the world, sorted by id`() {
    val seen = mutableListOf<List<Long>>()
    val plugin =
      plugin("p") { r -> r.every(8, "See.") { call -> seen += call.actors.map { it.id } } }
    val w = world(zone, plugins = PluginHost.of(plugin), bubble = true)
    val ann = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val wolf = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:b")))
    val dormant = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:e")))
    w.runPulses(8)
    val ids = seen.single()
    assertTrue(ids == ids.sorted(), "actors are sorted by id: $ids")
    assertTrue(ann.id.v in ids, "the player is simulated: $ids")
    assertTrue(wolf.id.v in ids, "a mob in a simulated room is listed: $ids")
    assertFalse(dormant.id.v in ids, "a mob in a dormant room is left alone: $ids")
  }

  @Test
  fun `every refuses a period below one combat round`() {
    val logged = mutableListOf<String>()
    val plugin = plugin("p") { r -> r.every(7, "Too fast.") {} }
    val host = PluginHost.of(plugin, log = { logged += it })
    assertEquals(emptyList(), host.ids)
    assertTrue(
      host.warnings.any { it.contains("every(): pulses must be at least 8, got 7") },
      host.warnings.toString(),
    )
  }

  @Test
  fun `a throwing periodic callback is diagnosed and still runs next time`() {
    var runs = 0
    val plugin =
      plugin("p") { r ->
        r.every(8, "Boom.") {
          runs++
          if (runs == 1) error("periodic boom")
        }
      }
    val w = world(zone, plugins = PluginHost.of(plugin))
    w.runPulses(16)
    assertEquals(2, runs)
    assertTrue(
      w.diagnostics.any { it.kind == "plugin" && it.message.contains("every(8)") },
      w.diagnostics.joinToString("\n") { it.message },
    )
  }

  @Test
  fun `a periodic callback can send a player text`() {
    val plugin =
      plugin("p") { r ->
        r.every(8, "Greet.") { call ->
          call.actors.forEach { call.world.send(it, "The tide turns.") }
        }
      }
    val w = world(zone, plugins = PluginHost.of(plugin))
    val ann = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val sink = w.connect(ann)
    w.runPulses(8)
    assertTrue(sink.drain().contains("The tide turns."), "the text arrives: ${sink.drain()}")
  }
}
