package gups.core.world

import gups.core.HookRef
import gups.core.Label
import gups.core.loop.PulseTimer
import gups.core.model.MobProto
import kotlin.test.Test
import kotlin.test.assertEquals

class SchedulerTest {
  private val ticker = Label("//t:ticker")

  @Test
  fun `phases run in order within a pulse`() {
    val host = RecordingHost()
    val log = mutableListOf<String>()
    val think = ThinkFn { w, _, _ ->
      log += "think@${w.pulse}"
      ThinkResult.Idle
    }
    val f =
      Fixture(
        rules = FixedRules(toHit = 0),
        scripts = host,
        protos =
          T.protos(
            extraMobs =
              listOf(MobProto(ticker, "a ticker", listOf("ticker"), think = think, thinkEvery = 1))
          ),
      )
    host.handlers[HookName.ON_COMBAT_TICK] = { w, owner, _ ->
      if (owner.name == "a ticker") log += "combat@${w.pulse}"
      null
    }
    host.handlers[HookName.ON_TICK] = { w, owner, _ ->
      if (owner.name == "a ticker") log += "tick@${w.pulse}"
      null
    }
    val m = f.mob(ticker)
    f.w.startCombat(m, f.mob(T.DUMMY))
    f.runTo(119)
    f.w.after(m, 1, HookRef(T.HALL, "fn"))
    log.clear()
    f.run()
    assertEquals(listOf("ref:fn@120"), host.calls.filter { it.startsWith("ref:") })
    assertEquals(listOf("think@120", "combat@120", "tick@120"), log)
  }

  @Test
  fun `combat rounds every 8 pulses and world ticks every 120`() {
    val host = RecordingHost()
    val rounds = mutableListOf<Long>()
    val ticks = mutableListOf<Long>()
    host.handlers[HookName.ON_COMBAT_TICK] = { w, owner, _ ->
      if (owner.name == "a goblin") rounds += w.pulse
      null
    }
    host.handlers[HookName.ON_TICK] = { w, owner, _ ->
      if (owner.name == "a goblin") ticks += w.pulse
      null
    }
    val f = Fixture(rules = FixedRules(toHit = 0), scripts = host)
    f.w.startCombat(f.mob(), f.mob(T.DUMMY))
    f.runTo(240)
    assertEquals((1..30).map { it * 8L }, rounds)
    assertEquals(listOf(120L, 240L), ticks)
  }

  @Test
  fun `timers fire in due then id order`() {
    val host = RecordingHost()
    val f = Fixture(scripts = host)
    val room = f.w.room(T.HALL)
    f.w.after(room, 3, HookRef(T.HALL, "b"))
    f.w.after(room, 2, HookRef(T.HALL, "a"))
    f.w.after(room, 3, HookRef(T.HALL, "c"))
    val cancelled = f.w.after(room, 3, HookRef(T.HALL, "gone"))
    f.w.cancel(room, cancelled)
    f.run(3)
    assertEquals(listOf("ref:a@2", "ref:b@3", "ref:c@3"), host.calls)
  }

  @Test
  fun `affects expire on their absolute pulse`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.applyAffect(
      p,
      AffectSpec("haste", 5, statDeltas = mapOf("dex" to 3), expireMessage = "You slow down."),
    )
    assertEquals(13, p.stat("dex"))
    f.run(4)
    assertEquals(13, p.stat("dex"))
    f.out(p)
    f.run()
    assertEquals(10, p.stat("dex"))
    assertEquals("You slow down.\n", f.out(p))
  }

  @Test
  fun `pulse timer skips missed boundaries instead of catching up`() {
    val t = PulseTimer(pulseNanos = 100, startNanos = 0)
    assertEquals(100, t.nextBoundary)
    assertEquals(0, t.pulseFinished(150))
    assertEquals(200, t.nextBoundary)
    // The pulse due at 200 finishes at 450: boundaries 300 and 400 are skipped.
    assertEquals(2, t.pulseFinished(450))
    assertEquals(500, t.nextBoundary)
    // Finishing exactly on the next boundary is not an overrun.
    assertEquals(0, t.pulseFinished(600))
    assertEquals(600, t.nextBoundary)
  }
}
