package gups.core.world

import gups.core.CapturingSink
import gups.core.ClientGmcpEvent
import gups.core.ClientInputEvent
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.loop.WorldLoop
import gups.core.model.Player
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class GmcpInputTest {
  private val f = Fixture()

  @Test
  fun `a registered package reaches its handler with its data`() {
    val p = f.player("Ann")
    val calls = mutableListOf<Pair<Player, Any?>>()
    f.w.pluginGmcpIn["shop.buy"] = GmcpInput("Shop.Buy") { pl, d -> calls += pl to d }
    f.w.handle(ClientGmcpEvent(p.session!!, "shop.BUY", mapOf("a" to 7L), false, f.nanos))
    val call = calls.single()
    assertEquals(p, call.first)
    assertEquals(mapOf("a" to 7L), call.second)
  }

  @Test
  fun `unknown packages are ignored`() {
    val p = f.player("Ann")
    val calls = mutableListOf<Player>()
    f.w.pluginGmcpIn["shop.buy"] = GmcpInput("Shop.Buy") { pl, _ -> calls += pl }
    repeat(10) { f.w.handle(ClientGmcpEvent(p.session!!, "nose.poke", null, false, f.nanos)) }
    assertEquals(emptyList(), calls)
    // Ten ignored messages counted no drops: one malformed message is still survivable.
    f.w.handle(ClientGmcpEvent(p.session!!, "shop.buy", null, true, f.nanos))
    assertEquals(emptyList(), calls, "the malformed message dropped, the handler never ran")
    assertNull(f.sink(p).closedReason)
  }

  @Test
  fun `messages before a character are dropped silently`() {
    val sink = CapturingSink()
    f.w.handle(WorldControlEvent.Connect(SessionId(99), sink))
    f.w.handle(ClientGmcpEvent(SessionId(99), "shop.buy", null, false, f.nanos))
    assertEquals("", sink.transcript)
    assertTrue(f.w.session(SessionId(99)) != null)
  }

  @Test
  fun `malformed messages for a registered package count as drops`() {
    val p = f.player("Ann")
    val sink = f.sink(p)
    f.w.pluginGmcpIn["shop.buy"] = GmcpInput("Shop.Buy") { _, _ -> }
    repeat(3) { f.w.handle(ClientGmcpEvent(p.session!!, "shop.buy", null, true, f.nanos)) }
    assertNull(sink.closedReason, "three drops are survivable")
    val id = p.session!!
    f.w.handle(ClientGmcpEvent(id, "shop.buy", null, true, f.nanos))
    assertEquals("Too much input.", sink.closedReason)
    assertNull(f.w.session(id))
  }

  @Test
  fun `the fast-lane bucket limits handled messages`() {
    val p = f.player("Ann")
    val calls = mutableListOf<Player>()
    f.w.pluginGmcpIn["shop.buy"] = GmcpInput("Shop.Buy") { pl, _ -> calls += pl }
    repeat(21) { f.w.handle(ClientGmcpEvent(p.session!!, "shop.buy", null, false, f.nanos)) }
    assertEquals(20, calls.size, "the bucket holds twenty messages per refill")
    assertContains(f.sink(p).drain(), "Slow down.")
  }

  @Test
  fun `a throwing handler records a diagnostic and keeps the session`() {
    val f = Fixture(strict = false)
    val p = f.player("Ann")
    f.w.pluginGmcpIn["shop.buy"] = GmcpInput("Shop.Buy") { _, _ -> error("boom") }
    f.w.handle(ClientGmcpEvent(p.session!!, "shop.buy", null, false, f.nanos))
    assertTrue(f.w.diagnostics.any { "GMCP input 'Shop.Buy'" in it.message })
    assertNull(f.sink(p).closedReason)
  }

  @Test
  fun `lines and GMCP keep their order through the loop`() = runBlocking {
    val f = Fixture()
    val p = f.w.spawnPlayer("Ann", T.HALL)
    val sink = CapturingSink()
    f.w.handle(WorldControlEvent.Connect(SessionId(99), sink))
    f.w.attach(SessionId(99), p)
    val seen = mutableListOf<Boolean>()
    f.w.pluginGmcpIn["shop.buy"] =
      GmcpInput("Shop.Buy") { _, _ -> seen += sink.transcript.contains("hi") }
    val loop = WorldLoop(f.w, pulseNanos = 2_000_000)
    val job = launch(WorldLoop.worldDispatcher) { loop.run() }
    loop.submit(ClientInputEvent(SessionId(99), "say hi", System.nanoTime()))
    loop.submit(ClientGmcpEvent(SessionId(99), "shop.buy", null, false, System.nanoTime()))
    withTimeout(5_000) { while (seen.isEmpty()) yield() }
    loop.stop()
    job.join()
    assertEquals(
      listOf(true),
      seen,
      "the typed line's effect reaches the session before the GMCP handler runs",
    )
  }

  @Test
  fun `gmcp names are compared ignoring case and on dot boundaries`() {
    assertTrue(gmcpOverlaps("Shop", "shop"))
    assertTrue(gmcpOverlaps("Shop.Buy", "Shop"))
    assertTrue(gmcpOverlaps("Shop", "shop.buy"))
    assertFalse(gmcpOverlaps("Shop", "Shopkeeper"))
    assertFalse(gmcpOverlaps("Shop.Buy", "Shop.Sell"))
    assertTrue(gmcpWithin("Core.Shop", "Core"))
    assertTrue(gmcpWithin("Core", "core"))
    assertFalse(gmcpWithin("CoreX", "Core"))
  }
}
