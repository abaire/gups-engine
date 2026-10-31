package gups.script

import gups.core.world.awardXp
import gups.core.world.flushOutput
import gups.core.world.pendingLevels
import gups.script.testing.TestWorlds
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeferredTrainerTest {
  private val hall = "//zones/ex:hall"
  private val ready = "You feel ready to advance."
  private val scoreReady = "You are ready to advance."

  private fun fixture() = ScriptFixture(TestWorlds.DEFERRED_TRAINER, load = listOf("zones/ex"))

  @Test
  fun `a player who earns a level is told READY, uses the trainer, gains the level, and sees the score ready line go away`() {
    val f = fixture()
    val p = f.player(at = hall)

    f.w.awardXp(p, 1000L)
    f.w.flushOutput()
    assertEquals(1, p.level)
    assertEquals(1, f.w.pendingLevels(p))
    assertContains(f.out(p), ready)

    f.input(p, "score")
    f.run(1)
    assertContains(f.out(p), scoreReady)

    f.input(p, "train")
    f.run(1)
    assertEquals(2, p.level)
    assertEquals(1000L, p.xp)
    assertEquals(0, f.w.pendingLevels(p))
    val trainOut = f.out(p)
    assertContains(trainOut, "You have reached level 2!")
    assertFalse(ready in trainOut, "READY is not repeated when no level is left pending")

    f.input(p, "score")
    f.run(1)
    assertFalse(scoreReady in f.out(p))
  }

  @Test
  fun `the trainer with nothing pending changes nothing`() {
    val f = fixture()
    val p = f.player(at = hall)
    f.w.awardXp(p, 500L)
    f.w.flushOutput()
    f.out(p)
    val stats = p.baseStats.toMap()
    val vitals = p.vitals.mapValues { (_, v) -> v.current to v.max }

    f.input(p, "train")
    f.run(1)

    assertEquals(1, p.level)
    assertEquals(500L, p.xp)
    assertEquals(0, f.w.pendingLevels(p))
    assertEquals(stats, p.baseStats.toMap())
    assertEquals(vitals, p.vitals.mapValues { (_, v) -> v.current to v.max })
    val out = f.out(p)
    assertFalse("You have reached level" in out)
    assertContains(out, "You have nothing to learn.")
  }

  @Test
  fun `trainer world passes lint without findings`() {
    val report = Linter(WorldDir(tempWorld(TestWorlds.DEFERRED_TRAINER))).lint()
    assertTrue(report.ok, report.findings.joinToString { it.message })
  }
}
