package gups.script

import gups.core.AttrValue
import gups.core.Label
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.Player
import gups.core.world.attach
import gups.core.world.flushOutput
import gups.core.world.handle
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GmcpVerbTest {
  /** Records text, GMCP messages, and the order they were delivered. */
  private class Sink(supports: Set<String>) : OutputSink {
    private val _supports = supports
    val order = mutableListOf<String>()
    val messages = mutableListOf<Pair<String, Any?>>()
    val text = StringBuilder()
    var closed: String? = null

    override fun send(text: String) {
      this.text.append(text)
      order += "text"
    }

    override fun gmcp(pkg: String, data: Any?) {
      messages += pkg to data
      order += pkg
    }

    override fun prompt(text: String) {
      order += "prompt"
    }

    override fun wantsGmcp(pkg: String) = _supports.any { pkg == it || pkg.startsWith("$it.") }

    override fun close(reason: String) {
      closed = reason
    }

    fun drainGmcp() = messages.toList().also { messages.clear() }

    fun reset() {
      order.clear()
      messages.clear()
      text.setLength(0)
    }
  }

  private val lib =
    "gmcp_package(\"Shop.Stock\", doc = \"Items for sale\")\n\n" +
      "def ships(ctx, actor, pkg, data):\n" +
      "    return ctx.gmcp(actor, pkg, data)\n"

  private val zone =
    "load(\"//lib:a.star\", \"ships\")\n\n" +
      "def _stock(ctx):\n" +
      "    r = ships(ctx, ctx.actor, \"Shop.Stock\", {\"item\": \"sword\"})\n" +
      "    ctx.set_attr(ctx.actor, \"sent\", r)\n" +
      "    ctx.send(ctx.actor, \"Stock sent.\")\n\n" +
      "def _stock_to(ctx):\n" +
      "    if not ctx.target:\n" +
      "        ctx.set_attr(ctx.actor, \"sent_to\", \"no target\")\n" +
      "        return\n" +
      "    ctx.set_attr(ctx.actor, \"sent_to\", ships(ctx, ctx.target, \"Shop.Stock\", {\"item\": \"sword\"}))\n\n" +
      "def _nested(ctx):\n" +
      "    ctx.set_attr(ctx.actor, \"nested\", ships(ctx, ctx.actor, \"Shop.Stock.Delta\", {\"d\": 1}))\n\n" +
      "def _direct(ctx):\n" +
      "    ctx.gmcp(ctx.actor, \"Shop.Stock\", {\"item\": \"sword\"})\n\n" +
      "def _undecl(ctx):\n" +
      "    ctx.gmcp(ctx.actor, \"Garden.Plants\", {\"x\": 1})\n\n" +
      "def _float(ctx):\n" +
      "    ships(ctx, ctx.actor, \"Shop.Stock\", {\"price\": 1.5})\n\n" +
      "def _none(ctx):\n" +
      "    r = ships(ctx, ctx.actor, \"Shop.Stock\", {\"a\": [1, None], \"b\": {\"c\": None}})\n" +
      "    ctx.set_attr(ctx.actor, \"nones\", r)\n\n" +
      "mob(\"rat\")\n" +
      "room(\"start\", commands = {\"stock\": _stock, \"stock_to\": _stock_to, \"nested\": _nested, " +
      "\"direct\": _direct, \"undecl\": _undecl, \"float\": _float, \"none\": _none})\n"

  private fun fixture() =
    ScriptFixture(
      mapOf(
        "lib/package.star" to libPkg(),
        "lib/a.star" to lib,
        "zones/z/package.star" to zonePkg(),
        "zones/z/rooms.star" to zone,
      ),
      load = listOf("zones/z", "lib"),
    )

  private var next = 1L

  private fun connect(f: ScriptFixture, name: String, supports: Set<String>): Pair<Player, Sink> {
    val p = f.w.spawnPlayer(name, Label("//zones/z:start"))
    val id = SessionId(next++)
    val sink = Sink(supports)
    f.w.handle(WorldControlEvent.Connect(id, sink))
    f.w.attach(id, p)
    f.w.flushOutput()
    sink.reset()
    return p to sink
  }

  /** What `stock_to` returned for [p]'s last use, cleared so each case is read fresh. */
  private fun sentTo(p: Player): AttrValue? = p.customAttributes.remove("z:sent_to")

  @Test
  fun `a lib helper sends under its declared package`() {
    val f = fixture()
    val (p, sink) = connect(f, "Ann", setOf("Shop"))
    f.input(p, "stock")
    f.run()
    assertEquals(AttrValue.B(true), p.customAttributes["z:sent"])
    val (pkg, data) = sink.drainGmcp().single()
    assertEquals("Shop.Stock", pkg)
    assertEquals(mapOf("item" to "sword"), data)
  }

  @Test
  fun `a nested name is allowed`() {
    val f = fixture()
    val (p, sink) = connect(f, "Ann", setOf("Shop"))
    f.input(p, "nested")
    f.run()
    assertEquals(AttrValue.B(true), p.customAttributes["z:nested"])
    assertEquals("Shop.Stock.Delta", sink.drainGmcp().single().first)
  }

  @Test
  fun `a zone calling gmcp directly fails and names the lib`() {
    val f = fixture()
    val (p, _) = connect(f, "Ann", setOf("Shop"))
    f.input(p, "direct")
    f.run()
    assertContains(
      f.diagnostics("script_error").single().message,
      "code in //zones/z didn't declare a GMCP package for 'Shop.Stock'",
    )
    assertContains(
      f.diagnostics("script_error").single().message,
      "use the helpers exported by //lib",
    )
  }

  @Test
  fun `an undeclared package fails`() {
    val f = fixture()
    val (p, _) = connect(f, "Ann", setOf("Shop"))
    f.input(p, "undecl")
    f.run()
    assertContains(
      f.diagnostics("script_error").single().message,
      "code in //zones/z didn't declare a GMCP package for 'Garden.Plants'",
    )
  }

  @Test
  fun `mobs, link-dead players and unsupported clients get False`() {
    val f = fixture()
    val (p, _) = connect(f, "Ann", setOf("Shop"))

    f.mob("//zones/z:rat")
    f.input(p, "stock_to rat")
    f.run()
    assertEquals(AttrValue.B(false), sentTo(p))

    val (bob, bobSink) = connect(f, "Bob", setOf("Shop"))
    f.input(p, "stock_to Bob")
    f.run()
    assertEquals(AttrValue.B(true), sentTo(p), "a connected Bob receives the event")
    bobSink.reset()
    f.w.handle(WorldControlEvent.Disconnect(bob.session!!))
    assertNotNull(bob.linkDeadSince, "Bob is link-dead")
    f.input(p, "stock_to Bob")
    f.run()
    assertEquals(AttrValue.B(false), sentTo(p))
    assertTrue(bobSink.drainGmcp().isEmpty(), "a link-dead player receives nothing")

    val (nobody, nSink) = connect(f, "Cid", emptySet())
    f.input(nobody, "stock")
    f.run()
    assertEquals(AttrValue.B(false), nobody.customAttributes["z:sent"])
    assertTrue(nSink.drainGmcp().isEmpty(), "an unsupported client receives nothing")
  }

  @Test
  fun `None is allowed inside lists and dicts`() {
    val f = fixture()
    val (p, sink) = connect(f, "Ann", setOf("Shop"))
    f.input(p, "none")
    f.run()
    assertEquals(AttrValue.B(true), p.customAttributes["z:nones"])
    assertEquals(
      mapOf("a" to listOf(1L, null), "b" to mapOf("c" to null)),
      sink.drainGmcp().single().second,
    )
  }

  @Test
  fun `floats raise a script error`() {
    val f = fixture()
    val (p, _) = connect(f, "Ann", setOf("Shop"))
    f.input(p, "float")
    f.run()
    assertContains(f.diagnostics("script_error").single().message, "floats can't be sent")
  }

  @Test
  fun `the event goes out after the hook's text`() {
    val f = fixture()
    val (p, sink) = connect(f, "Ann", setOf("Shop"))
    f.input(p, "stock")
    f.run()
    assertEquals(listOf("text", "Shop.Stock", "prompt"), sink.order)
    assertTrue(sink.text.contains("Stock sent."))
  }
}
