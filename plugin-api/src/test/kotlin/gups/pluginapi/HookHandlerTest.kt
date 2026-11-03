package gups.pluginapi

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.world.EntityRef
import gups.core.world.callHook
import gups.core.world.spawnItem
import gups.pluginapi.host.PluginHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plugin handlers for custom hooks: order, arguments, failures, the read-only world. */
class HookHandlerTest {
  private val zone =
    mapOf(
      "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
      "zones/z/rooms.star" to
        """
        def _price(ctx, base):
            return base * 2

        room("a", name = "Glade", desc = "A glade.", flags = ["dead_end_ok"])
        mob("wolf", name = "a wolf", desc = "A wolf.", keywords = ["wolf"], hooks = {"shop_price": _price})
        item("stick", name = "a stick", desc = "A stick.", keywords = ["stick"], value = 5)
        """,
    )

  private fun hookPlugin(id: String, fn: (PluginHookCall) -> Any?): GupsPlugin =
    object : GupsPlugin {
      override val id = id
      override val pluginApiVersion = PluginApi.VERSION

      override fun register(r: PluginRegistry) {
        r.addHook("shop_price", "Raises the price.", fn)
      }
    }

  @Test
  fun `handlers run after the entity's hook, in plugin-id order, each seeing the previous result`() {
    val one = hookPlugin("aaa") { call -> listOf(call.result, "aaa") }
    val two = hookPlugin("zzz") { call -> listOf(call.result, "zzz") }
    val w = world(zone, plugins = PluginHost.of(two, one))
    val mob = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))
    assertEquals(
      listOf(listOf(20L, "aaa"), "zzz"),
      w.callHook(mob, "shop_price", mapOf("base" to 10L)),
    )
  }

  @Test
  fun `Kotlin ints and collections become plain values for the next handler and the caller`() {
    var seenByNext: Any? = null
    val one = hookPlugin("aaa") { mapOf("price" to 7, "tags" to setOf("a")) }
    val two = hookPlugin("zzz") { call -> call.result.also { seenByNext = it } }
    val w = world(zone, plugins = PluginHost.of(one, two))
    val room = w.room(Label("//zones/z:a"))
    val expected = mapOf("price" to 7L, "tags" to listOf("a"))
    assertEquals(expected, w.callHook(room, "shop_price", emptyMap()))
    assertEquals(expected, seenByNext)
  }

  @Test
  fun `a float result is refused and keeps the result`() {
    val plugin = hookPlugin("floaty") { 1.5 }
    val w = world(zone, plugins = PluginHost.of(plugin))
    val mob = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))
    assertEquals(20L, w.callHook(mob, "shop_price", mapOf("base" to 10L)))
    assertTrue(
      w.diagnostics.any { it.kind == "plugin" && it.message.contains("hook 'shop_price'") },
      w.diagnostics.joinToString("\n") { it.message },
    )
  }

  @Test
  fun `a handler runs for an entity without its own hook`() {
    val plugin = hookPlugin("pricey") { call -> if (call.result == null) 42L else call.result }
    val w = world(zone, plugins = PluginHost.of(plugin))
    val room = w.room(Label("//zones/z:a"))
    assertEquals(42L, w.callHook(room, "shop_price", emptyMap()))
  }

  @Test
  fun `entity arguments arrive as plugin views`() {
    var seen: PluginHookCall? = null
    val plugin =
      object : GupsPlugin {
        override val id = "spy"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addHook(
            "value",
            "Reads the item.",
            {
              seen = it
              it.result
            },
          )
        }
      }
    val w = world(zone, plugins = PluginHost.of(plugin))
    val room = w.room(Label("//zones/z:a"))
    val stick = w.spawnItem(Label("//zones/z:stick"), ItemLocation.InRoom(room.id, 0))
    val mob = w.spawnMob(Label("//zones/z:wolf"), room)
    w.callHook(
      mob,
      "value",
      mapOf("item" to EntityRef(stick.id), "others" to listOf<EntityRef>(EntityRef(stick.id))),
    )
    val call = seen!!
    assertTrue(call.entity is PluginActor)
    assertEquals(5, (call.args["item"] as PluginItem).value)
    assertEquals(5, ((call.args["others"] as List<*>)[0] as PluginItem).value)
  }

  @Test
  fun `a throwing handler records a diagnostic and keeps the result`() {
    val plugin = hookPlugin("boom") { error("explodes") }
    val w = world(zone, plugins = PluginHost.of(plugin))
    val mob = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))
    assertEquals(20L, w.callHook(mob, "shop_price", mapOf("base" to 10L)))
    assertTrue(
      w.diagnostics.any { it.kind == "plugin" && it.message.contains("hook 'shop_price'") },
      w.diagnostics.joinToString("\n") { it.message },
    )
  }

  @Test
  fun `a non-plain result is refused and keeps the result`() {
    class NotPlain
    val plugin = hookPlugin("weird") { NotPlain() }
    val w = world(zone, plugins = PluginHost.of(plugin))
    val mob = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))
    assertEquals(20L, w.callHook(mob, "shop_price", mapOf("base" to 10L)))
    assertTrue(
      w.diagnostics.any { it.kind == "plugin" && it.message.contains("hook 'shop_price'") },
      w.diagnostics.joinToString("\n") { it.message },
    )
  }

  @Test
  fun `the handler's world can't change anything`() {
    var seen: PluginHookCall? = null
    val plugin =
      object : GupsPlugin {
        override val id = "spy"
        override val pluginApiVersion = PluginApi.VERSION

        override fun register(r: PluginRegistry) {
          r.addHook(
            "shop_price",
            "Reads the world.",
            {
              seen = it
              it.result
            },
          )
        }
      }
    val w = world(zone, plugins = PluginHost.of(plugin))
    val mob = w.spawnMob(Label("//zones/z:wolf"), w.room(Label("//zones/z:a")))
    w.callHook(mob, "shop_price", emptyMap())
    assertTrue(seen!!.world !is PluginWorld, "the handler's world is mutable")
  }
}
