package gups.pluginapi

import gups.core.ClientGmcpEvent
import gups.core.Label
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import gups.core.world.World
import gups.core.world.attach
import gups.core.world.flushOutput
import gups.core.world.handle
import gups.core.world.recentInputs
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.WorldFacade
import gups.pluginapi.testplugins.ShopGmcpPlugin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach

class GmcpInputTest {
  private val w: World = world(plugins = PluginHost.of(ShopGmcpPlugin()))

  @BeforeEach
  fun clear() {
    ShopGmcpPlugin.calls.clear()
    ShopGmcpPlugin.lastCall = null
    ShopGmcpPlugin.twiceError = null
  }

  private class Sink(val supports: Set<String>) : OutputSink {
    val text = mutableListOf<String>()
    val gmcp = mutableListOf<Pair<String, Any?>>()

    override fun send(text: String) {
      this.text += text
    }

    override fun gmcp(pkg: String, data: Any?) {
      gmcp += pkg to data
    }

    override fun wantsGmcp(pkg: String) = supports.any { pkg == it || pkg.startsWith("$it.") }

    override fun close(reason: String) = Unit
  }

  private fun connect(supports: Set<String> = emptySet()): Pair<Player, Sink> {
    val p = w.spawnPlayer("Ann", Label("//zones/z:a"))
    val sink = Sink(supports)
    w.handle(WorldControlEvent.Connect(SessionId(1), sink), 0)
    w.attach(SessionId(1), p)
    w.flushOutput()
    return p to sink
  }

  private fun message(p: Player, data: Any?) =
    w.handle(ClientGmcpEvent(p.session!!, "shop.buy", data, false, 1_000_000_000))

  @Test
  fun `the wiring lists a plugin's state, event and input packages`() {
    val host = PluginHost.of(ShopGmcpPlugin())
    assertEquals(
      setOf("Shop.Stocks", "Shop.Stock", "Shop.Buy", "Shop.Stocks.Buy"),
      host.gmcpPackages,
    )
  }

  @Test
  fun `a client message runs the handler with plain data`() {
    val (p, _) = connect()
    message(p, mapOf("item" to "sword"))
    assertEquals(1, ShopGmcpPlugin.calls.size)
    assertEquals("Shop.Buy", ShopGmcpPlugin.calls.single().first)
    assertEquals(mapOf("item" to "sword"), ShopGmcpPlugin.calls.single().second)
  }

  @Test
  fun `command queues a tick-lane line that runs at the next pulse and is recorded`() {
    val (p, sink) = connect()
    message(p, "cmd")
    assertEquals(listOf("buy"), w.recentInputs.map { it.line })
    w.runPulse()
    w.flushOutput()
    assertTrue(sink.text.joinToString().contains("Bought."), sink.text.toString())
  }

  @Test
  fun `command can be called only once per message`() {
    val (p, _) = connect()
    message(p, "twice")
    assertEquals(
      "command() runs at most one line per GMCP message",
      ShopGmcpPlugin.twiceError!!.message,
    )
  }

  @Test
  fun `the handler's world can't change anything`() {
    val (p, _) = connect()
    message(p, "expose")
    assertFailsWith<ClassCastException> { ShopGmcpPlugin.lastCall!!.world as PluginWorld }
  }

  @Test
  fun `a handler can reply with a GMCP event`() {
    val (p, sink) = connect(supports = setOf("Shop.Stock"))
    message(p, "reply")
    w.flushOutput()
    assertEquals(1, sink.gmcp.size)
    assertEquals("Shop.Stock", sink.gmcp.single().first)
    assertEquals(mapOf("item" to "sword"), sink.gmcp.single().second)
  }

  @Test
  fun `every facade of a plugin may send its declared GMCP events`() {
    val (p, sink) = connect(supports = setOf("Shop.Stock"))
    // Builtins, traits, restrictions and filters build their own facade from the plugin id.
    val facade = WorldFacade(w, "shop") {}
    assertTrue(facade.gmcp(ActorFacade(p, w), "Shop.Stock", mapOf("item" to "axe")))
    assertFailsWith<IllegalArgumentException> {
      WorldFacade(w, "other") {}.gmcp(ActorFacade(p, w), "Shop.Stock", null)
    }
    w.flushOutput()
    assertEquals(listOf<Pair<String, Any?>>("Shop.Stock" to mapOf("item" to "axe")), sink.gmcp)
  }

  @Test
  fun `a throwing handler records a plugin diagnostic`() {
    val (p, _) = connect()
    message(p, "throw")
    assertTrue(
      w.diagnostics.any { it.kind == "plugin" && "gmcp handler exploded" in it.message },
      w.diagnostics.map { it.message }.toString(),
    )
  }
}
