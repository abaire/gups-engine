package gups.core.world

import gups.core.fixtures.FixtureRules
import gups.core.model.DamageType
import gups.core.model.LevelGain
import gups.core.rules.LevelUpResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LevelingTest {
  @Test
  fun `on_level_up stats, max and current deltas apply, current is capped at new maximum, and record holds stats and max only`() {
    val rules = FixtureRules()
    val seenLevels = mutableListOf<Int>()
    rules.levelUpOf = { c ->
      seenLevels += c.actor!!.level
      LevelUpResult(
        stats = mapOf("str" to 2, "dex" to 0),
        max = mapOf("hp" to 5),
        current = mapOf("hp" to 100),
      )
    }
    val f = Fixture(rules = rules)
    val p = f.player("Ann", hp = 20)
    p.xp = 1000L
    val initialStr = p.stat("str")
    val initialMaxHp = p.baseMax("hp")

    assertTrue(f.w.levelUp(p))
    assertEquals(2, p.level)
    assertEquals(initialStr + 2, p.stat("str"))
    assertEquals(initialMaxHp + 5, p.baseMax("hp"))
    assertEquals(initialMaxHp + 5, p.current("hp"))
    assertEquals(LevelGain(stats = mapOf("str" to 2), max = mapOf("hp" to 5)), p.levelGains[2])
    assertEquals(listOf(2), seenLevels, "on_level_up sees the new level")
    assertTrue(p.dirty)
    f.w.flushOutput()
    assertEquals("You have reached level 2!\n", f.out(p))

    // Mob level-up behaves identically.
    val m = f.mob(T.DUMMY)
    m.xp = 1000L
    val mobInitialStr = m.stat("str")
    val mobInitialMaxHp = m.baseMax("hp")
    assertTrue(f.w.levelUp(m))
    assertEquals(2, m.level)
    assertEquals(mobInitialStr + 2, m.stat("str"))
    assertEquals(mobInitialMaxHp + 5, m.baseMax("hp"))
    assertEquals(LevelGain(stats = mapOf("str" to 2), max = mapOf("hp" to 5)), m.levelGains[2])
  }

  @Test
  fun `a rules error in on_level_up still raises the level, applies nothing and records nothing`() {
    val rules = FixtureRules()
    rules.levelUpOf = { error("rules error") }
    val f = Fixture(rules = rules)
    val alerts = mutableListOf<String>()
    f.w.onAlert = { alerts += it }
    val p = f.player("Ann", hp = 20)
    p.xp = 1000L
    val initialStr = p.stat("str")
    val initialMaxHp = p.baseMax("hp")
    val initialHp = p.current("hp")

    assertTrue(f.w.levelUp(p))
    assertEquals(2, p.level)
    assertEquals(initialStr, p.stat("str"))
    assertEquals(initialMaxHp, p.baseMax("hp"))
    assertEquals(initialHp, p.current("hp"))
    assertNull(p.levelGains[2])
    assertEquals(1, alerts.size, "the rules error is reported")
    assertContains(alerts.single(), "on_level_up")
    f.w.flushOutput()
    assertEquals("You have reached level 2!\n", f.out(p))

    // A null result (neutral fallback) also raises the level without applying or recording gains.
    p.xp = 2000L
    rules.levelUpOf = { null }
    assertTrue(f.w.levelUp(p))
    assertEquals(3, p.level)
    assertEquals(initialStr, p.stat("str"))
    assertEquals(initialMaxHp, p.baseMax("hp"))
    assertNull(p.levelGains[3])
  }

  @Test
  fun `levelUp with nothing pending returns false and changes nothing`() {
    val rules = FixtureRules()
    rules.levelUpOf = { LevelUpResult(stats = mapOf("str" to 2)) }
    val f = Fixture(rules = rules)
    val p = f.player("Ann", hp = 20)
    p.xp = 500L
    f.w.flushOutput()
    f.out(p)
    p.dirty = false
    val initialStr = p.stat("str")
    val initialVitals = p.vitalMap.mapValues { (_, v) -> v.current to v.max }

    assertFalse(f.w.levelUp(p))
    assertEquals(1, p.level)
    assertEquals(500L, p.xp)
    assertEquals(initialStr, p.stat("str"))
    assertEquals(initialVitals, p.vitalMap.mapValues { (_, v) -> v.current to v.max })
    assertTrue(p.levelGains.isEmpty())
    assertFalse(p.dirty)
    f.w.flushOutput()
    assertEquals("", f.out(p))
  }

  @Test
  fun `pendingLevels stops at a non-increasing xp_to_level with one rules_error alert, and is 0 when the threshold is never reached`() {
    val rules = FixtureRules()
    val f = Fixture(rules = rules)
    val alerts = mutableListOf<String>()
    f.w.onAlert = { alerts += it }

    val p = f.player("Ann")
    p.xp = 500L
    assertEquals(0, f.w.pendingLevels(p))
    assertTrue(alerts.isEmpty(), "no alert when threshold is never reached")

    rules.xpToLevelOf = { 1000L }
    p.xp = 5000L
    assertEquals(1, f.w.pendingLevels(p))
    assertEquals(1, alerts.size, "expected exactly one alert")
    assertContains(alerts.single(), "xp_to_level")
    assertEquals(f.w.pulse, f.w.alertTimes["rules_error"])
  }

  @Test
  fun `a lowered maximum caps the current vital and is recorded`() {
    val rules = FixtureRules()
    rules.levelUpOf = { LevelUpResult(max = mapOf("hp" to -5)) }
    val f = Fixture(rules = rules)
    val p = f.player("Ann", hp = 20)
    p.xp = 1000L
    val initialMaxHp = p.baseMax("hp")
    assertEquals(initialMaxHp, p.current("hp"))

    assertTrue(f.w.levelUp(p))
    assertEquals(initialMaxHp - 5, p.baseMax("hp"))
    assertEquals(initialMaxHp - 5, p.current("hp"))
    assertEquals(LevelGain(stats = emptyMap(), max = mapOf("hp" to -5)), p.levelGains[2])
  }

  @Test
  fun `a level that gives no gains replaces an older record for that level`() {
    val rules = FixtureRules()
    rules.levelUpOf = { LevelUpResult(stats = mapOf("str" to 0), current = mapOf("hp" to 3)) }
    val f = Fixture(rules = rules)
    val p = f.player("Ann", hp = 20)
    p.xp = 1000L
    p.levelGains[2] = LevelGain(stats = mapOf("str" to 9), max = emptyMap())

    assertTrue(f.w.levelUp(p))
    assertNull(p.levelGains[2])
  }

  @Test
  fun `a kill that crosses two thresholds applies two steps in order and sends two LEVEL_UP messages and one GMCP refresh`() {
    val rules = FixtureRules()
    rules.xpForKillOf = { 2500L }
    val f = Fixture(rules = rules)
    val (p, sink) = f.gmcpPlayer("Ann")
    f.run()
    sink.take()

    val mob = f.mob()
    f.w.damage(mob, mapOf(f.fs.health to 100), DamageType(f.fs.unarmed), p)

    assertEquals(3, p.level)
    assertEquals(2500L, p.xp)

    // Flushing without the end-of-tick GMCP pass shows the refresh was queued by the award itself.
    f.w.flush(f.w.sessions.getValue(p.session!!))
    val out = sink.drain()
    assertContains(out, "You gain 2500 experience.")
    val level2Index = out.indexOf("You have reached level 2!")
    val level3Index = out.indexOf("You have reached level 3!")
    assertTrue(level2Index != -1, "level 2 message sent")
    assertTrue(level3Index != -1, "level 3 message sent")
    assertTrue(level2Index < level3Index, "level 2 message precedes level 3 message")

    val statusUpdates = sink.take().filter { it.first == "Char.Status" }
    assertEquals(1, statusUpdates.size, "expected exactly one GMCP Char.Status refresh")
    @Suppress("UNCHECKED_CAST") val status = statusUpdates.single().second as Map<String, Any?>
    assertEquals(3, status["level"])
    assertEquals(2500L, status["xp"])
    assertEquals(3000L, status["xp_next"])

    f.run()
    assertTrue(sink.take().none { it.first == "Char.Status" }, "the tick does not resend it")
  }

  @Test
  fun `an award stops at a non-increasing xp_to_level with one rules_error alert`() {
    val rules = FixtureRules()
    rules.xpToLevelOf = { level -> if (level <= 2) 1000L * level else 2000L }
    val f = Fixture(rules = rules)
    val alerts = mutableListOf<String>()
    f.w.onAlert = { alerts += it }
    val p = f.player("Ann")

    f.w.awardXp(p, 10_000L)

    assertEquals(3, p.level)
    assertEquals(1, alerts.size)
    assertContains(alerts.single(), "xp_to_level")
    assertEquals(0, f.w.pendingLevels(p))
  }

  @Test
  fun `an award that owes more than 100 levels applies 100, and the next award applies more`() {
    val f = Fixture()
    val p = f.player("Ann")
    assertEquals(1, p.level)

    // With xpToLevel(level) = 1000L * level, level 100 -> 101 requires 100,000 XP.
    // 200,000 XP would be enough for level 201 (>100 levels).
    f.w.awardXp(p, 200_000L)
    assertEquals(101, p.level, "capped at 100 levels on first award")
    assertEquals(100, f.w.pendingLevels(p), "levels still pending after cap")

    f.w.awardXp(p, 1L)
    assertEquals(201, p.level, "next award applies more pending levels")
  }

  @Test
  fun `awardXp ignores non-positive amounts`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.awardXp(p, 0L)
    f.w.awardXp(p, -500L)
    assertEquals(0L, p.xp)
    assertEquals(1, p.level)
  }

  @Test
  fun `gains that used ctx rng unwind exactly, stats and maxima equal their values before the level-up`() {
    val rules = FixtureRules()
    rules.levelUpOf = { c ->
      val rolledStr = c.rng.int(1, 9)
      val rolledHpMax = c.rng.int(5, 19)
      LevelUpResult(stats = mapOf("str" to rolledStr), max = mapOf("hp" to rolledHpMax))
    }
    val f = Fixture(rules = rules)
    val p = f.player("Ann", hp = 20)
    p.xp = 1000L
    val initialStr = p.stat("str")
    val initialMaxHp = p.baseMax("hp")

    assertTrue(f.w.levelUp(p))
    assertEquals(2, p.level)
    val gainedStr = p.stat("str") - initialStr
    val gainedMaxHp = p.baseMax("hp") - initialMaxHp
    assertTrue(gainedStr in 1..9)
    assertTrue(gainedMaxHp in 5..19)
    assertEquals(
      LevelGain(stats = mapOf("str" to gainedStr), max = mapOf("hp" to gainedMaxHp)),
      p.levelGains[2],
    )

    assertEquals(1, f.w.levelDown(p))
    assertEquals(1, p.level)
    assertEquals(initialStr, p.stat("str"))
    assertEquals(initialMaxHp, p.baseMax("hp"))
    assertNull(p.levelGains[2])
  }

  @Test
  fun `a level with no record unwinds nothing and still drops`() {
    val f = Fixture()
    val p = f.player("Ann", hp = 20)
    p.level = 2
    p.xp = 1500L
    val otherLevel = LevelGain(stats = mapOf("str" to 4), max = mapOf("hp" to 7))
    p.levelGains[3] = otherLevel
    val initialStr = p.stat("str")
    val initialMaxHp = p.baseMax("hp")

    assertEquals(1, f.w.levelDown(p))
    assertEquals(1, p.level)
    assertEquals(initialStr, p.stat("str"))
    assertEquals(initialMaxHp, p.baseMax("hp"))
    assertEquals(mapOf(3 to otherLevel), p.levelGains, "records for other levels are left alone")
  }

  @Test
  fun `unwinding a max gain caps the current vital at the lowered maximum`() {
    val rules = FixtureRules()
    rules.levelUpOf = { LevelUpResult(max = mapOf("hp" to 10), current = mapOf("hp" to 10)) }
    val f = Fixture(rules = rules)
    val p = f.player("Ann", hp = 20)
    p.xp = 1000L
    val initialMaxHp = p.baseMax("hp")
    assertEquals(initialMaxHp, p.current("hp"))

    assertTrue(f.w.levelUp(p))
    assertEquals(initialMaxHp + 10, p.current("hp"))

    assertEquals(1, f.w.levelDown(p))
    assertEquals(initialMaxHp, p.baseMax("hp"))
    assertEquals(initialMaxHp, p.current("hp"))
  }

  @Test
  fun `levelDown sends one immediate GMCP Char Status refresh for several steps`() {
    val f = Fixture()
    val (p, sink) = f.gmcpPlayer("Ann")
    p.level = 4
    p.xp = 4000L
    f.run()
    sink.take()

    assertEquals(2, f.w.levelDown(p, 2))

    // Flushing without the end-of-tick GMCP pass shows the refresh was queued by levelDown itself.
    f.w.flush(f.w.sessions.getValue(p.session!!))
    sink.drain()
    val statusUpdates = sink.take().filter { it.first == "Char.Status" }
    assertEquals(1, statusUpdates.size, "expected exactly one GMCP Char.Status refresh")
    @Suppress("UNCHECKED_CAST") val status = statusUpdates.single().second as Map<String, Any?>
    assertEquals(2, status["level"])
    assertEquals(1999L, status["xp"])
    assertEquals(2000L, status["xp_next"])
  }

  @Test
  fun `xp is clamped to xp_to_level(level) - 1, and any other pending levels disappear`() {
    val f = Fixture()
    val p = f.player("Ann")
    p.level = 2
    p.xp = 4000L
    assertEquals(3, f.w.pendingLevels(p))

    assertEquals(1, f.w.levelDown(p, 1))
    assertEquals(1, p.level)
    assertEquals(999L, p.xp)
    assertEquals(0, f.w.pendingLevels(p), "all pending levels disappeared")
  }

  @Test
  fun `levelDown count 3 from level 2 stops at 1 and returns 1`() {
    val f = Fixture()
    val p = f.player("Ann")
    p.level = 2
    p.xp = 1500L

    assertEquals(1, f.w.levelDown(p, 3))
    assertEquals(1, p.level)
    assertEquals(0, f.w.levelDown(p, 3), "cannot step down below level 1")
    assertEquals(1, p.level)
  }

  @Test
  fun `levelDown sends LEVEL_DOWN once for several steps`() {
    val f = Fixture()
    val p = f.player("Ann")
    p.level = 4
    p.xp = 4000L
    f.w.flushOutput()
    f.out(p)

    assertEquals(2, f.w.levelDown(p, 2))
    assertEquals(2, p.level)
    f.w.flushOutput()
    assertEquals("You have fallen to level 2.\n", f.out(p))
  }

  @Test
  fun `a mob unwinds like a player`() {
    val rules = FixtureRules()
    rules.levelUpOf = { LevelUpResult(stats = mapOf("str" to 3), max = mapOf("hp" to 10)) }
    val f = Fixture(rules = rules)
    val m = f.mob(T.DUMMY)
    m.xp = 1000L
    val initialStr = m.stat("str")
    val initialMaxHp = m.baseMax("hp")

    assertTrue(f.w.levelUp(m))
    assertEquals(2, m.level)
    assertEquals(initialStr + 3, m.stat("str"))
    assertEquals(initialMaxHp + 10, m.baseMax("hp"))
    assertEquals(LevelGain(stats = mapOf("str" to 3), max = mapOf("hp" to 10)), m.levelGains[2])

    assertEquals(1, f.w.levelDown(m))
    assertEquals(1, m.level)
    assertEquals(initialStr, m.stat("str"))
    assertEquals(initialMaxHp, m.baseMax("hp"))
    assertNull(m.levelGains[2])
  }

  @Test
  fun `takeXp returns the amount actually removed, never goes below 0 and never changes the level`() {
    val f = Fixture()
    val p = f.player("Ann")
    p.level = 2
    p.xp = 500L
    f.w.flushOutput()
    f.out(p)

    assertEquals(200L, f.w.takeXp(p, 200L))
    assertEquals(300L, p.xp)
    assertEquals(2, p.level)
    assertTrue(p.dirty)

    assertEquals(300L, f.w.takeXp(p, 500L), "clamped to remaining xp")
    assertEquals(0L, p.xp)
    assertEquals(2, p.level)

    assertEquals(0L, f.w.takeXp(p, 100L), "returns 0 when xp is 0")
    assertEquals(0L, p.xp)
    assertEquals(2, p.level)

    assertEquals(0L, f.w.takeXp(p, 0L))
    assertEquals(0L, f.w.takeXp(p, -50L))
    assertEquals(0L, p.xp)
    assertEquals(2, p.level)

    f.w.flushOutput()
    assertEquals("", f.out(p), "takeXp sends nothing")
  }

  @Test
  fun `a lost level is saved by an immediate checkpoint`() {
    class Captures : Persistence {
      val immediate = mutableListOf<String>()

      override fun checkpoint(world: World, cause: String, immediate: Boolean) {
        if (immediate) this.immediate += cause
      }
    }

    val f = Fixture()
    val captures = Captures()
    f.w.persistence = captures

    val p = f.player("Ann")
    p.level = 2
    p.xp = 1500L
    f.run()
    captures.immediate.clear()

    assertEquals(1, f.w.levelDown(p))
    f.run()
    assertEquals(listOf("level"), captures.immediate)
  }
}
