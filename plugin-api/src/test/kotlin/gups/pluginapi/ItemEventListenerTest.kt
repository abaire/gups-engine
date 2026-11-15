package gups.pluginapi

import gups.core.CapturingSink
import gups.core.Label
import gups.core.SessionId
import gups.core.WORLD_TICK_PULSES
import gups.core.WorldControlEvent
import gups.core.model.ItemLocation
import gups.core.world.attach
import gups.core.world.destroy
import gups.core.world.handle
import gups.core.world.spawnItem
import gups.core.world.submit
import gups.pluginapi.host.PluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `r.onItemEvent` listeners: they run after the script global hooks for item events, carry the item
 * and its ctx fields (`actor`, `event`), and cannot veto or change the event.
 */
class ItemEventListenerTest {
  private val zone =
    mapOf(
      "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
      "zones/z/rooms.star" to
        """
        room("a", name = "Glade", desc = "A glade.", flags = ["dead_end_ok"])
        """,
      "zones/z/items.star" to
        """
        item("gem", name = "a gem", desc = "A gem.", keywords = ["gem"])
        item("dust", name = "some dust", desc = "Dust.", keywords = ["dust"], decay_pulses = 1)
        def _use(ctx):
            pass
        item("scroll", name = "a scroll", desc = "A scroll.", keywords = ["scroll"], charges = 1, empty = "destroy", on_use = _use)
        def _escape(ctx):
            ctx.move_item(ctx.self, ctx.room)
        item("key", name = "a brass key", desc = "A key.", keywords = ["key"], on_destroy = _escape)
        item("satchel", name = "a satchel", desc = "A satchel.", keywords = ["satchel"], container = container())
        """,
    )

  private fun itemEventPlugin(
    id: String,
    listeners: List<Pair<String, (PluginItemEventCall) -> Unit>>,
  ): GupsPlugin =
    object : GupsPlugin {
      override val id = id
      override val pluginApiVersion = PluginApi.VERSION

      override fun register(r: PluginRegistry) {
        for ((name, fn) in listeners) r.onItemEvent(name, "Listens to $name.", fn)
      }
    }

  @Test
  fun `an on_destroy listener runs after the script global hook, with item inWorld, holder actor, and cause destroy`() {
    var calls = 0
    var seen: String? = null
    var inWorldSeen = false
    var itemNameSeen: String? = null
    var actorSeen: PluginActor? = null
    var eventSeen: Any? = null
    var rootSeen: String? = null
    val plugin =
      itemEventPlugin(
        "itemlog",
        listOf(
          "on_destroy" to
            { call ->
              calls++
              actorSeen = call.fields["actor"] as? PluginActor
              seen = call.world.getAttr(actorSeen!!.room, "lib:destroy_log", "") as String?
              inWorldSeen = call.item.inWorld
              itemNameSeen = call.item.name
              eventSeen = call.fields["event"]
              rootSeen = (call.fields["destroyed_root"] as? PluginItem)?.name
            }
        ),
      )
    val w =
      world(
        files =
          zone +
            mapOf(
              "lib/package.star" to "package(api_version = 1, kind = \"lib\")",
              "lib/g.star" to
                """
                def _g(ctx):
                    ctx.set_attr(ctx.room, "destroy_log", ctx.get_attr(ctx.room, "destroy_log", "") + "script,")
                global_hook("on_destroy", _g)
                """,
            ),
        plugins = PluginHost.of(plugin),
      )
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val gem = w.spawnItem(Label("//zones/z:gem"), ItemLocation.Carried(p.id))
    w.destroy(gem)
    assertEquals(1, calls, "the on_destroy listener must run exactly once")
    assertEquals("script,", seen, "the plugin must run after the script global hook")
    assertTrue(inWorldSeen, "the item must still be in the world when on_destroy runs")
    assertEquals("a gem", itemNameSeen)
    assertEquals("Ann", actorSeen?.name)
    assertEquals("destroy", eventSeen)
    assertEquals("a gem", rootSeen, "destroyed_root must be the item itself for the root")
    assertNull(w.item(gem.id), "the item must be removed after destroy")
  }

  @Test
  fun `an on_destroy listener on decay has null actor on the ground and event decay`() {
    var calls = 0
    var actorSeen: Any? = "unset"
    var eventSeen: Any? = null
    val plugin =
      itemEventPlugin(
        "decay",
        listOf(
          "on_destroy" to
            { call ->
              calls++
              actorSeen = call.fields["actor"]
              eventSeen = call.fields["event"]
            }
        ),
      )
    val w = world(zone, plugins = PluginHost.of(plugin))
    val room = w.room(Label("//zones/z:a"))
    val dust = w.spawnItem(Label("//zones/z:dust"), ItemLocation.InRoom(room.id, 0))
    repeat(WORLD_TICK_PULSES + 2) { w.runPulse() }
    assertEquals(1, calls, "the on_destroy listener must run once on decay")
    assertNull(actorSeen, "actor must be null when the item is on the ground")
    assertEquals("decay", eventSeen)
    assertNull(w.item(dust.id))
  }

  @Test
  fun `an on_destroy listener on last charge has event empty and the holder actor`() {
    var calls = 0
    var actorSeen: PluginActor? = null
    var eventSeen: Any? = null
    val plugin =
      itemEventPlugin(
        "empty",
        listOf(
          "on_destroy" to
            { call ->
              calls++
              actorSeen = call.fields["actor"] as? PluginActor
              eventSeen = call.fields["event"]
            }
        ),
      )
    val w = world(zone, plugins = PluginHost.of(plugin))
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val scroll = w.spawnItem(Label("//zones/z:scroll"), ItemLocation.Carried(p.id))
    val sink = CapturingSink()
    w.handle(WorldControlEvent.Connect(SessionId(1), sink))
    w.attach(SessionId(1), p)
    w.submit(p, "use scroll")
    w.runPulse()
    assertEquals(
      1,
      calls,
      "the on_destroy listener must run once when empty=destroy spends last charge",
    )
    assertEquals("Ann", actorSeen?.name)
    assertEquals("empty", eventSeen)
    assertNull(w.item(scroll.id))
  }

  @Test
  fun `an item that escapes its container destruction does not reach onItemEvent`() {
    val seenNames = mutableListOf<String>()
    val plugin =
      itemEventPlugin("esc", listOf("on_destroy" to { call -> seenNames += call.item.name }))
    val w = world(zone, plugins = PluginHost.of(plugin))
    val room = w.room(Label("//zones/z:a"))
    val satchel = w.spawnItem(Label("//zones/z:satchel"), ItemLocation.InRoom(room.id, 0))
    val key = w.spawnItem(Label("//zones/z:key"), ItemLocation.Contained(satchel.id))
    w.destroy(satchel)
    assertEquals(listOf("a satchel"), seenNames)
    assertNull(w.item(satchel.id))
    assertTrue(w.item(key.id) != null, "escaped key must survive")
  }

  @Test
  fun `listeners run in plugin-id order, then registration order within a plugin`() {
    val order = mutableListOf<String>()
    val beta =
      itemEventPlugin(
        "beta",
        listOf("on_destroy" to { order += "beta-1" }, "on_destroy" to { order += "beta-2" }),
      )
    val alpha = itemEventPlugin("alpha", listOf("on_destroy" to { order += "alpha" }))
    val w = world(zone, plugins = PluginHost.of(beta, alpha))
    val room = w.room(Label("//zones/z:a"))
    val gem = w.spawnItem(Label("//zones/z:gem"), ItemLocation.InRoom(room.id, 0))
    w.destroy(gem)
    assertEquals(listOf("alpha", "beta-1", "beta-2"), order)
  }

  @Test
  fun `a throwing listener records a plugin diagnostic, the next listener still runs, and the item is gone`() {
    val order = mutableListOf<String>()
    val plugin =
      itemEventPlugin(
        "boom",
        listOf(
          "on_destroy" to
            {
              order += "first"
              error("boom")
            },
          "on_destroy" to { order += "second" },
        ),
      )
    val w = world(zone, plugins = PluginHost.of(plugin))
    val room = w.room(Label("//zones/z:a"))
    val gem = w.spawnItem(Label("//zones/z:gem"), ItemLocation.InRoom(room.id, 0))
    w.destroy(gem)
    assertEquals(listOf("first", "second"), order, "the second listener must still run")
    assertNull(w.item(gem.id), "the item must still be removed when a listener throws")
    assertTrue(
      w.diagnostics.any { it.kind == "plugin" && it.message.contains("on_destroy") },
      w.diagnostics.joinToString("\n") { it.message },
    )
  }

  @Test
  fun `onItemEvent with an invalid event name fails register with the expected message`() {
    var registered = false
    val bad =
      object : GupsPlugin {
        override val id = "bad"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.onItemEvent("on_death", "Nope.") { _ -> }
          registered = true
        }
      }
    val warnings = mutableListOf<String>()
    val host = PluginHost.of(bad, log = { warnings += it })
    assertFalse(registered, "register must be interrupted at the invalid onItemEvent")
    assertTrue(host.ids.isEmpty(), "the failing plugin must be skipped")
    assertTrue(
      warnings.any {
        it.contains("register failed") &&
          it.contains("onItemEvent(): 'on_death' is not an item event; expected one of on_destroy")
      },
      warnings.joinToString("\n"),
    )
  }

  @Test
  fun `onEvent still rejects on_destroy as not an actor event`() {
    var registered = false
    val bad =
      object : GupsPlugin {
        override val id = "bad"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.onEvent("on_destroy", "Nope.") { _ -> }
          registered = true
        }
      }
    val warnings = mutableListOf<String>()
    val host = PluginHost.of(bad, log = { warnings += it })
    assertFalse(registered, "register must be interrupted at the invalid onEvent")
    assertTrue(host.ids.isEmpty(), "the failing plugin must be skipped")
    assertTrue(
      warnings.any { it.contains("register failed") && it.contains("on_destroy") },
      warnings.joinToString("\n"),
    )
  }
}
