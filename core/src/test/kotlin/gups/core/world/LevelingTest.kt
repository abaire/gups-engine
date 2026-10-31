package gups.core.world

import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchemas
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
    p.xp = 3000L
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

    rules.xpToLevelOf = { _, _ -> 1000L }
    p.xp = 5000L
    assertEquals(1, f.w.pendingLevels(p))
    assertEquals(1, alerts.size, "expected exactly one alert")
    assertContains(alerts.single(), "xp_to_level")
    assertEquals(f.w.pulse, f.w.alertTimes["rules_error"])
  }

  @Test
  fun `pendingLevels on a non-increasing xp_to_level, called repeatedly within one window, raises one rules_error alert, and raises another after the window passes`() {
    val rules = FixtureRules()
    val f = Fixture(rules = rules)
    val alerts = mutableListOf<String>()
    f.w.onAlert = { alerts += it }

    rules.xpToLevelOf = { _, _ -> 1000L }
    val p = f.player("Ann")
    p.xp = 5000L

    assertEquals(1, f.w.pendingLevels(p))
    assertEquals(1, alerts.size)
    assertContains(alerts.single(), "xp_to_level")

    // Repeated calls within the quarantine window raise no new alert
    assertEquals(1, f.w.pendingLevels(p))
    f.run(10)
    assertEquals(1, f.w.pendingLevels(p))
    assertEquals(1, alerts.size)

    // After the quarantine window passes, another alert is raised
    f.runTo(f.w.quarantineWindowPulses)
    assertEquals(1, f.w.pendingLevels(p))
    assertEquals(2, alerts.size)
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
    rules.xpForKillOf = { 3000L }
    val f = Fixture(rules = rules)
    val (p, sink) = f.gmcpPlayer("Ann")
    f.run()
    sink.take()

    val mob = f.mob()
    f.w.damage(mob, mapOf(f.fs.health to 100), DamageType(f.fs.unarmed), p)

    assertEquals(3, p.level)
    assertEquals(3000L, p.xp)

    // Flushing without the end-of-tick GMCP pass shows the refresh was queued by the award itself.
    f.w.flush(f.w.sessions.getValue(p.session!!))
    val out = sink.drain()
    assertContains(out, "You gain 3000 experience.")
    val level2Index = out.indexOf("You have reached level 2!")
    val level3Index = out.indexOf("You have reached level 3!")
    assertTrue(level2Index != -1, "level 2 message sent")
    assertTrue(level3Index != -1, "level 3 message sent")
    assertTrue(level2Index < level3Index, "level 2 message precedes level 3 message")

    val statusUpdates = sink.take().filter { it.first == "Char.Status" }
    assertEquals(1, statusUpdates.size, "expected exactly one GMCP Char.Status refresh")
    @Suppress("UNCHECKED_CAST") val status = statusUpdates.single().second as Map<String, Any?>
    assertEquals(3, status["level"])
    assertEquals(3000L, status["xp"])
    assertEquals(6000L, status["xp_next"])

    f.run()
    assertTrue(sink.take().none { it.first == "Char.Status" }, "the tick does not resend it")
  }

  @Test
  fun `an award stops at a non-increasing xp_to_level with one rules_error alert`() {
    val rules = FixtureRules()
    rules.xpToLevelOf = { _, level -> if (level <= 2) 1000L * level else 2000L }
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

    // Level 100 ends at 5,050,000 XP and level 244 at 29,890,000, so 30,000,000 XP owes more
    // than 200 levels.
    f.w.awardXp(p, 30_000_000L)
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
  fun `lowering a level clamps xp using that actor's own curve`() {
    val rules = FixtureRules()
    rules.xpToLevelOf = { actor, level ->
      if (actor.name == "Fast") 1000L * level else 2000L * level
    }
    val f = Fixture(rules = rules)
    val fast = f.player("Fast")
    fast.level = 2
    fast.xp = 5000L

    val slow = f.player("Slow")
    slow.level = 2
    slow.xp = 5000L

    assertEquals(1, f.w.levelDown(fast))
    assertEquals(1, fast.level)
    assertEquals(999L, fast.xp)

    assertEquals(1, f.w.levelDown(slow))
    assertEquals(1, slow.level)
    assertEquals(1999L, slow.xp)
  }

  @Test
  fun `a ruleset whose xp_to_level depends on an actor attribute owes different levels to two actors with the same XP, and Char Status xp_next differs between them`() {
    val rules = FixtureRules()
    rules.xpToLevelOf = { actor, level ->
      if (actor.name == "Fast") 1000L * level else 2000L * level
    }
    val f = Fixture(rules = rules)
    val (fast, fastSink) = f.gmcpPlayer("Fast")
    val (slow, slowSink) = f.gmcpPlayer("Slow")

    fast.xp = 1500L
    slow.xp = 1500L

    assertEquals(1, f.w.pendingLevels(fast))
    assertEquals(0, f.w.pendingLevels(slow))

    fastSink.take()
    slowSink.take()

    f.w.refreshGmcp(fast, "Char.Status")
    f.w.refreshGmcp(slow, "Char.Status")
    f.w.flush(f.w.sessions.getValue(fast.session!!))
    f.w.flush(f.w.sessions.getValue(slow.session!!))

    @Suppress("UNCHECKED_CAST")
    val fastStatus =
      fastSink.take().single { it.first == "Char.Status" }.second as Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    val slowStatus =
      slowSink.take().single { it.first == "Char.Status" }.second as Map<String, Any?>

    assertEquals(1000L, fastStatus["xp_next"])
    assertEquals(2000L, slowStatus["xp_next"])
  }

  @Test
  fun `levelDown sends one immediate GMCP Char Status refresh for several steps`() {
    val f = Fixture()
    val (p, sink) = f.gmcpPlayer("Ann")
    p.level = 4
    p.xp = 7000L
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
    assertEquals(2999L, status["xp"])
    assertEquals(3000L, status["xp_next"])
  }

  @Test
  fun `xp is clamped to xp_to_level(level) - 1, and any other pending levels disappear`() {
    val f = Fixture()
    val p = f.player("Ann")
    p.level = 2
    p.xp = 10_000L
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

  @Test
  fun `world levelMode defaults to AUTO and can be set to DEFERRED`() {
    val schema = FixtureSchemas.FANTASY.schema
    val protos = T.protos(FixtureSchemas.FANTASY)
    val rules = FixedRules(FixtureSchemas.FANTASY)

    val w = World(protos, schema, rules)
    assertEquals(LevelMode.AUTO, w.levelMode)
    w.levelMode = LevelMode.DEFERRED
    assertEquals(LevelMode.DEFERRED, w.levelMode)

    val wDeferred = World(protos, schema, rules, levelMode = LevelMode.DEFERRED)
    assertEquals(LevelMode.DEFERRED, wDeferred.levelMode)
  }

  @Test
  fun `XP crossing a threshold sends READY once, a second award does not repeat it, and the level does not change`() {
    val f = Fixture()
    f.w.levelMode = LevelMode.DEFERRED
    val p = f.player("Ann")
    assertEquals(1, p.level)
    assertEquals(0L, p.xp)

    // Crossing the first threshold (1000 xp) sends GAIN and READY, and does not change level.
    f.w.awardXp(p, 1000L)
    assertEquals(1, p.level)
    assertEquals(1000L, p.xp)
    assertEquals(1, f.w.pendingLevels(p))
    f.w.flushOutput()
    assertEquals("You gain 1000 experience.\nYou feel ready to advance.\n", f.out(p))

    // A second award while already pending does not repeat READY.
    f.w.awardXp(p, 500L)
    assertEquals(1, p.level)
    assertEquals(1500L, p.xp)
    assertEquals(1, f.w.pendingLevels(p))
    f.w.flushOutput()
    assertEquals("You gain 500 experience.\n", f.out(p))

    // Crossing another threshold while levels are already pending also does not repeat READY.
    f.w.awardXp(p, 1500L)
    assertEquals(1, p.level)
    assertEquals(3000L, p.xp)
    assertEquals(2, f.w.pendingLevels(p))
    f.w.flushOutput()
    assertEquals("You gain 1500 experience.\n", f.out(p))
  }

  @Test
  fun `READY is sent again when an award crosses a threshold after pending levels were used up`() {
    val f = Fixture()
    f.w.levelMode = LevelMode.DEFERRED
    val p = f.player("Ann")
    f.w.awardXp(p, 1000L)
    assertTrue(f.w.levelUpAndNotify(p))
    assertEquals(0, f.w.pendingLevels(p))
    f.w.flushOutput()
    f.out(p)

    f.w.awardXp(p, 2000L)
    assertEquals(2, p.level)
    assertEquals(1, f.w.pendingLevels(p))
    f.w.flushOutput()
    assertEquals("You gain 2000 experience.\nYou feel ready to advance.\n", f.out(p))
  }

  @Test
  fun `levelUpAndNotify applies one level and sends READY again only if another is pending`() {
    val f = Fixture()
    f.w.levelMode = LevelMode.DEFERRED
    val p = f.player("Ann")
    f.w.awardXp(p, 3000L)
    assertEquals(2, f.w.pendingLevels(p))
    f.w.flushOutput()
    f.out(p)

    // First levelUpAndNotify applies one level; one level is still pending, so READY is sent.
    assertTrue(f.w.levelUpAndNotify(p))
    assertEquals(2, p.level)
    assertEquals(1, f.w.pendingLevels(p))
    f.w.flushOutput()
    assertEquals("You have reached level 2!\nYou feel ready to advance.\n", f.out(p))

    // Second levelUpAndNotify applies the second level; 0 pending, so READY is not sent.
    assertTrue(f.w.levelUpAndNotify(p))
    assertEquals(3, p.level)
    assertEquals(0, f.w.pendingLevels(p))
    f.w.flushOutput()
    assertEquals("You have reached level 3!\n", f.out(p))

    // Third call with nothing pending returns false, changes nothing, and sends nothing.
    assertFalse(f.w.levelUpAndNotify(p))
    assertEquals(3, p.level)
    f.w.flushOutput()
    assertEquals("", f.out(p))

    // Works for mobs too.
    val m = f.mob(T.DUMMY)
    m.xp = 1000L
    assertTrue(f.w.levelUpAndNotify(m))
    assertEquals(2, m.level)
  }

  @Test
  fun `levelUpAndNotify sends one immediate GMCP Char Status refresh for a player when it raises the level, and none when nothing is pending`() {
    val f = Fixture()
    f.w.levelMode = LevelMode.DEFERRED
    val (p, sink) = f.gmcpPlayer("Ann")
    f.run()
    sink.take()

    // Nothing pending: levelUpAndNotify returns false and sends no GMCP updates.
    assertFalse(f.w.levelUpAndNotify(p))
    f.w.flush(f.w.sessions.getValue(p.session!!))
    sink.drain()
    assertTrue(
      sink.take().none { it.first == "Char.Status" },
      "no GMCP refresh when nothing is pending",
    )

    // With a pending level: levelUpAndNotify raises the level and sends one immediate Char.Status
    // refresh.
    p.xp = 1000L
    sink.take()
    assertTrue(f.w.levelUpAndNotify(p))
    assertEquals(2, p.level)

    f.w.flush(f.w.sessions.getValue(p.session!!))
    sink.drain()
    val statusUpdates = sink.take().filter { it.first == "Char.Status" }
    assertEquals(1, statusUpdates.size, "expected exactly one GMCP Char.Status refresh")
    @Suppress("UNCHECKED_CAST") val status = statusUpdates.single().second as Map<String, Any?>
    assertEquals(2, status["level"])
  }

  @Test
  fun `levelUp and levelDownStep dispatch ON_LEVEL_CHANGE with oldLevel and newLevel`() {
    val host = RecordingHost()
    val dispatches = mutableListOf<Triple<String, Int?, Int?>>()
    host.handlers[HookName.ON_LEVEL_CHANGE] = { _, owner, ctx ->
      dispatches += Triple(owner.name, ctx.oldLevel, ctx.newLevel)
      null
    }
    val f = Fixture(scripts = host)
    val p = f.player("Ann")
    p.xp = 1000L

    assertTrue(f.w.levelUp(p))
    assertEquals(2, p.level)
    assertEquals(
      listOf<Triple<String, Int?, Int?>>(Triple("Ann", 1, 2), Triple("The Hall", 1, 2)),
      dispatches,
    )

    dispatches.clear()
    f.w.levelDownStep(p)
    assertEquals(1, p.level)
    assertEquals(
      listOf<Triple<String, Int?, Int?>>(Triple("Ann", 2, 1), Triple("The Hall", 2, 1)),
      dispatches,
    )
  }

  @Test
  fun `score shows the ready line only while a level is pending, in either level mode`() {
    for (mode in LevelMode.entries) {
      val f = Fixture()
      val p = f.player("Ann")
      f.w.levelMode = mode

      f.input(p, "score")
      f.run(4)
      val out1 = f.out(p)
      assertTrue("Level 1 (0 xp)" in out1, "$mode")
      assertFalse("You are ready to advance." in out1, "$mode")

      // Setting XP directly bypasses awardXp, so the level stays pending even in auto mode.
      p.xp = 1000L
      assertEquals(1, f.w.pendingLevels(p), "$mode")
      f.input(p, "score")
      f.run(4)
      assertTrue("Level 1 (1000 xp)\nYou are ready to advance." in f.out(p), "$mode")

      assertTrue(f.w.levelUp(p), "$mode")
      f.input(p, "score")
      f.run(4)
      val out3 = f.out(p)
      assertTrue("Level 2 (1000 xp)" in out3, "$mode")
      assertFalse("You are ready to advance." in out3, "$mode")
    }
  }
}
