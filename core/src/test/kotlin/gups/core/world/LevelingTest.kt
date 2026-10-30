package gups.core.world

import gups.core.fixtures.FixtureRules
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
}
