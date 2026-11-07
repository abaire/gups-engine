package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.CreationAttempt
import gups.core.rules.RulesContext
import gups.core.rules.StartingStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreationAttemptTest {
  @Test
  fun `seed derivation is stable and depends on key, count, and worldSeed`() {
    val a1 = CreationAttempt("Hero", "a:1", 0, 42L)
    val a2 = CreationAttempt("Hero2", "a:1", 0, 42L)
    assertEquals(a1.seed, a2.seed, "Name does not affect the seed")

    // Pinned value check
    assertEquals(-5692346161014211544L, a1.seed)

    val aDiffKey = CreationAttempt("Hero", "a:2", 0, 42L)
    assertNotEquals(a1.seed, aDiffKey.seed)

    val aDiffCount = CreationAttempt("Hero", "a:1", 1, 42L)
    assertNotEquals(a1.seed, aDiffCount.seed)

    val aDiffWorldSeed = CreationAttempt("Hero", "a:1", 0, 999L)
    assertNotEquals(a1.seed, aDiffWorldSeed.seed)
  }

  @Test
  fun `rng streams are independent and deterministic`() {
    val attempt = CreationAttempt("Hero", "a:1", 0, 42L)
    val s0 = attempt.rng(0)
    val s1 = attempt.rng(1)

    // Different streams differ
    val draws0 = List(5) { s0.int(1, 1000) }
    val draws1 = List(5) { s1.int(1, 1000) }
    assertNotEquals(draws0, draws1)

    // Same stream called again yields identical sequence from the beginning
    val s3a = attempt.rng(3)
    val draws3a = List(5) { s3a.int(1, 1000) }
    val s3b = attempt.rng(3)
    val draws3b = List(5) { s3b.int(1, 1000) }
    assertEquals(draws3a, draws3b)
  }

  @Test
  fun `answersChanged clears state, finished and preview`() {
    val attempt = CreationAttempt("Hero", "a:1", 0, 42L)
    val f = Fixture()
    attempt.start = Label("//t:hall")
    val preview = f.w.previewPlayer(attempt)
    attempt.preview = preview
    attempt.state["foo"] = "bar"
    attempt.finished.add("step1")

    attempt.answersChanged()

    assertTrue(attempt.state.isEmpty())
    assertTrue(attempt.finished.isEmpty())
    assertNull(attempt.preview)
  }

  @Test
  fun `previewPlayer uses attempt startingRng and does not advance world rng or enter world`() {
    val fs = FixtureSchemas.FANTASY
    class RollRules : FixedRules(fs) {
      override fun startingStats(c: RulesContext): StartingStats {
        val rolled = c.rng.int(1, 1000)
        return StartingStats(stats = mapOf(fs.strength to rolled))
      }
    }

    val f1 = Fixture(seed = 100, fs = fs, rules = RollRules())
    val fTwin = Fixture(seed = 100, fs = fs, rules = RollRules())

    val attempt1 = CreationAttempt("Hero", "a:1", 0, 100L)
    attempt1.start = Label("//t:hall")
    val preview1 = f1.w.previewPlayer(attempt1)

    val attempt2 = CreationAttempt("Hero", "a:1", 0, 100L)
    attempt2.start = Label("//t:hall")
    val preview2 = f1.w.previewPlayer(attempt2)

    // Previews with same attempt seed give same rolled stat
    assertEquals(preview1.baseStat(fs.strength), preview2.baseStat(fs.strength))

    // World rng was not advanced
    val worldNext1 = f1.w.rng.int(1, 10000)
    val worldTwinNext = fTwin.w.rng.int(1, 10000)
    assertEquals(worldTwinNext, worldNext1)

    // Player is not in the world
    assertFalse(f1.w.players.contains(preview1))
  }
}
