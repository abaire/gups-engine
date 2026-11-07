package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureRules
import gups.core.fixtures.FixtureSchemas
import gups.core.model.CreationAttempt
import gups.core.model.CreationChoice
import gups.core.model.CreationProvider
import gups.core.model.CreationStep
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.rules.StartingStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreationSpawnTest {
  private val fs = FixtureSchemas.FANTASY

  private class PrepareHeritage(
    val onPrepare: (World, Player, CreationAttempt) -> Unit = { _, _, _ -> }
  ) : CreationProvider {
    override val choices: List<CreationChoice> = emptyList()
    override val steps: List<CreationStep> = emptyList()

    override fun available(
      world: World,
      choice: String,
      answers: Map<String, String>,
    ): List<String> = emptyList()

    override fun validate(world: World, answers: Map<String, String>): String? = null

    override fun startRoom(world: World, answers: Map<String, String>): Label? = null

    override fun created(world: World, player: Player, newCharacter: Boolean) = Unit

    override fun prepare(world: World, player: Player, attempt: CreationAttempt) {
      onPrepare(world, player, attempt)
    }
  }

  private fun fixture(rules: FixtureRules = FixtureRules(fs), seed: Long = 42L): Fixture {
    return Fixture(
      seed = seed,
      fs = fs,
      rules = rules,
      protos = Prototypes(T.rooms, T.mobs(fs), T.items, T.skills(fs)),
    )
  }

  @Test
  fun `prepare raises stats and max_hp, and current hp equals new effective maximum`() {
    val f = fixture()
    val provider = PrepareHeritage { w, p, _ ->
      w.setBaseStat(p, fs.strength, p.baseStat(fs.strength) + 3)
      w.setBaseMaxVital(p, fs.health, p.baseMax(fs.health) + 10)
    }
    f.w.creation = provider
    val attempt = CreationAttempt("Hero", CreationAttempt.nameKey("Hero"), 0, f.w.config.seed)
    val p = f.w.spawnPlayer("Hero", T.HALL, attempt = attempt)

    assertEquals(13, p.baseStat(fs.strength)) // 10 base + 3
    assertEquals(30, p.baseMax(fs.health)) // 20 base + 10
    assertEquals(30, p.max(fs.health))
    assertEquals(30, p.current(fs.health))
  }

  @Test
  fun `starting_stats that returns rads current vitals keeps rads at 0 after prepare`() {
    val rules =
      FixtureRules(fs).apply {
        startingOf = { _ -> StartingStats(max = fs.playerMax, current = mapOf(fs.energy to 0)) }
      }
    val f = fixture(rules = rules)
    val provider = PrepareHeritage { w, p, _ -> w.setBaseMaxVital(p, fs.energy, 50) }
    f.w.creation = provider
    val attempt = CreationAttempt("Hero", CreationAttempt.nameKey("Hero"), 0, f.w.config.seed)
    val p = f.w.spawnPlayer("Hero", T.HALL, attempt = attempt)

    assertEquals(50, p.max(fs.energy))
    assertEquals(0, p.current(fs.energy))
  }

  @Test
  fun `with a randomizing starting_stats, the spawned stats equal the attempt preview stats`() {
    val rules =
      FixtureRules(fs).apply {
        startingOf = { c ->
          val rolled = c.rng.int(3, 18)
          StartingStats(stats = mapOf(fs.strength to rolled))
        }
      }
    val f = fixture(rules = rules)
    val provider = PrepareHeritage()
    f.w.creation = provider
    val attempt = CreationAttempt("Hero", CreationAttempt.nameKey("Hero"), 0, f.w.config.seed)
    attempt.start = T.HALL
    val preview = f.w.previewPlayer(attempt)
    val previewStr = preview.stat(fs.strength)

    val p = f.w.spawnPlayer("Hero", T.HALL, attempt = attempt)
    assertEquals(previewStr, p.stat(fs.strength))
  }

  @Test
  fun `the preview stands in the provider's start room, so room-dependent stats match the spawn`() {
    val rules =
      FixtureRules(fs).apply {
        startingOf = { c ->
          val str = if (c.requireActor().room.label == T.YARD) 15 else 8
          StartingStats(stats = mapOf(fs.strength to str))
        }
      }
    val f = fixture(rules = rules)
    f.w.creation =
      object : CreationProvider by PrepareHeritage() {
        override fun startRoom(world: World, answers: Map<String, String>): Label = T.YARD
      }
    val attempt = CreationAttempt("Hero", CreationAttempt.nameKey("Hero"), 0, f.w.config.seed)
    attempt.start = T.HALL

    val preview = f.w.previewPlayer(attempt)
    val p =
      f.w.spawnPlayer("Hero", f.w.creationStartRoom(attempt.answers, T.HALL), attempt = attempt)

    assertEquals(T.YARD, preview.room.label)
    assertEquals(15, preview.baseStat(fs.strength))
    assertEquals(preview.baseStat(fs.strength), p.baseStat(fs.strength))
  }

  @Test
  fun `throwing prepare sets attempt prepareError, records diagnostic, and character is created`() {
    val f = fixture()
    val err = IllegalStateException("prepare boom")
    val provider = PrepareHeritage { _, _, _ -> throw err }
    f.w.creation = provider
    val attempt = CreationAttempt("Hero", CreationAttempt.nameKey("Hero"), 0, f.w.config.seed)
    val p = f.w.spawnPlayer("Hero", T.HALL, attempt = attempt)

    assertEquals("Hero", p.name)
    assertEquals(err, attempt.prepareError)
    assertTrue(f.w.diagnostics.any { it.kind == "plugin" && "prepare() failed" in it.message })
  }

  @Test
  fun `world rng is in the same state as in a twin world where no character was created`() {
    val rules =
      FixtureRules(fs).apply {
        startingOf = { c ->
          val rolled = c.rng.int(3, 18)
          StartingStats(stats = mapOf(fs.strength to rolled))
        }
      }
    val f1 = fixture(rules = rules, seed = 12345L)
    val f2 = fixture(rules = rules, seed = 12345L)

    val attempt = CreationAttempt("Hero", CreationAttempt.nameKey("Hero"), 0, f1.w.config.seed)
    f1.w.creation = PrepareHeritage()
    f1.w.spawnPlayer("Hero", T.HALL, attempt = attempt)

    val roll1 = f1.w.rng.int(1, 1000)
    val roll2 = f2.w.rng.int(1, 1000)
    assertEquals(roll2, roll1)
  }
}
