package gups.core.world

import gups.core.CapturingSink
import gups.core.ClientInputEvent
import gups.core.IDLE_QUIT_PULSES
import gups.core.LINKDEAD_PULSES
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.loop.WorldLoop
import gups.core.model.ItemLocation
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

class SessionTest {
  @Test
  fun `a link-dead character stays and fights, then quits after three minutes`() {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    f.w.startCombat(p, f.mob())
    f.input(p, "north")
    f.w.disconnect(p.session!!)
    assertTrue(p.actionQueue.isEmpty())
    f.run(LINKDEAD_PULSES - 1)
    assertTrue(p.inWorld)
    assertTrue(p.isEngaged)
    f.run()
    assertFalse(p.inWorld)
  }

  @Test
  fun `reconnecting resumes a link-dead character`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.disconnect(p.session!!)
    f.run(10)
    val sink = f.connect(p)
    f.input(p, "look")
    assertContains(sink.drain(), "The Hall")
    f.run(LINKDEAD_PULSES)
    assertTrue(p.inWorld)
  }

  @Test
  fun `idle players are quit unless exempt`() {
    val f = Fixture()
    val idle = f.player("Ann")
    val staff = f.player("Bea")
    staff.idleExempt = true
    f.run(IDLE_QUIT_PULSES)
    assertFalse(idle.inWorld)
    assertTrue(staff.inWorld)
  }

  @Test
  fun `quit is refused in combat and removes the character otherwise`() {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    val key = f.w.spawnItem(T.KEY, ItemLocation.Carried(p.id))
    val g = f.mob()
    f.w.startCombat(p, g)
    f.input(p, "quit")
    assertContains(f.out(p), "No way!")
    f.w.stopCombat(p)
    val sink = f.sink(p)
    f.input(p, "quit")
    assertFalse(p.inWorld)
    assertEquals("Goodbye.", sink.closedReason)
    assertTrue(f.w.item(key.id) == null)
  }

  @Test
  fun `input without a character or login handler is answered and the session closed`() {
    val f = Fixture()
    val sink = CapturingSink()
    f.w.handle(WorldControlEvent.Connect(SessionId(99), sink))
    f.w.handle(ClientInputEvent(SessionId(99), "look", System.nanoTime()))
    assertEquals("You are not playing a character.\n", sink.transcript)
    assertEquals("You are not playing a character.", sink.closedReason)
    assertEquals(null, f.w.session(SessionId(99)))
  }

  @Test
  fun `the world loop applies input between pulses on its own thread`() = runBlocking {
    val f = Fixture()
    val p = f.w.spawnPlayer("Ann", T.HALL)
    val sink = CapturingSink()
    f.w.handle(WorldControlEvent.Connect(SessionId(99), sink))
    f.w.attach(SessionId(99), p)
    val loop = WorldLoop(f.w, pulseNanos = 2_000_000)
    val job = launch(WorldLoop.worldDispatcher) { loop.run() }
    loop.submit(ClientInputEvent(SessionId(99), "north", System.nanoTime()))
    withTimeout(5_000) {
      while (withContext(WorldLoop.worldDispatcher) { p.room.label } != T.YARD) yield()
    }
    loop.stop()
    job.join()
    assertContains(sink.transcript, "The Yard")
    assertTrue(f.w.pulse > 0)
  }

  @Test
  fun `the loop never drops an event that arrives as a pulse falls due`() {
    val f = Fixture()
    // Short pulses so the wait's timeout keeps racing events posted from another thread.
    val loop = WorldLoop(f.w, pulseNanos = 50_000)
    val handled = AtomicInteger()
    val runner = thread { runBlocking { loop.run() } }
    try {
      val n = 5_000
      val random = Random(1)
      repeat(n) {
        loop.submit(WorldControlEvent.Task { handled.incrementAndGet() })
        // Let the loop park, so the event lands while it waits.
        LockSupport.parkNanos(random.nextLong(100_000))
      }
      val deadline = System.nanoTime() + 10_000_000_000
      while (handled.get() < n && System.nanoTime() < deadline) Thread.sleep(5)
      assertEquals(n, handled.get())
    } finally {
      loop.stop()
      runner.join(5000)
    }
  }

  @Test
  fun `shutdown stops the loop at its pulse`() = runBlocking {
    val f = Fixture()
    val loop = WorldLoop(f.w, pulseNanos = 1_000_000)
    loop.submit(WorldControlEvent.Shutdown(5, "maintenance"))
    withTimeout(5_000) { loop.run() }
    assertEquals(5, f.w.pulse)
  }

  @Test
  fun `invariant violations fail the pulse`() {
    val f = Fixture()
    val p = f.player("Ann")
    assertEquals(emptyList(), f.w.checkInvariants())
    p.trackIndex = 9
    assertFailsWith<InvariantViolation> { f.run() }
  }

  @Test
  fun `items can never form containment cycles`() {
    val f = Fixture()
    val hall = f.w.room(T.HALL)
    val outer = f.w.spawnItem(T.BAG, ItemLocation.InRoom(hall.id, 0))
    val inner = f.w.spawnItem(T.BAG, ItemLocation.Contained(outer.id))
    assertFailsWith<IllegalArgumentException> {
      f.w.moveItem(outer, ItemLocation.Contained(inner.id))
    }
    assertFailsWith<IllegalArgumentException> {
      f.w.moveItem(outer, ItemLocation.Contained(outer.id))
    }
    assertEquals(emptyList(), f.w.checkInvariants())
  }
}
