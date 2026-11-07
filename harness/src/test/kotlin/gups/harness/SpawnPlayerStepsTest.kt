package gups.harness

import gups.core.Label
import gups.core.model.CreationAttempt
import gups.core.model.CreationChoice
import gups.core.model.CreationProvider
import gups.core.model.CreationStep
import gups.core.model.Player
import gups.core.model.StepReply
import gups.core.model.StepView
import gups.core.world.World
import gups.core.world.setBaseStat
import gups.script.WorldDir
import gups.script.WorldPluginWiring
import gups.script.testing.TestWorlds
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpawnPlayerStepsTest {
  private class StatsStep : CreationStep {
    override val id = "stats"
    override val title = "Stats"

    override fun applies(world: World, attempt: CreationAttempt) = true

    override fun show(world: World, attempt: CreationAttempt) = StepView("Allocate stats:")

    override fun input(world: World, attempt: CreationAttempt, line: String): StepReply {
      return when (line) {
        "str +2" -> {
          attempt.state["bonus_str"] = 2
          StepReply.Stay("Added 2 str")
        }
        "fail_prepare" -> {
          attempt.state["fail_prepare"] = true
          StepReply.Stay("will fail prepare")
        }
        "done" -> StepReply.Done
        else -> StepReply.Stay("Unknown command")
      }
    }

    override fun summary(world: World, attempt: CreationAttempt): String? = "Stats configured"
  }

  private class SkippedStep : CreationStep {
    override val id = "skip"
    override val title = "Skip"

    override fun applies(world: World, attempt: CreationAttempt) = false

    override fun show(world: World, attempt: CreationAttempt) = StepView("skip")

    override fun input(world: World, attempt: CreationAttempt, line: String) = StepReply.Done

    override fun summary(world: World, attempt: CreationAttempt): String? = null
  }

  private class ThrowsStep : CreationStep {
    override val id = "err"
    override val title = "Err"

    override fun applies(world: World, attempt: CreationAttempt) = true

    override fun show(world: World, attempt: CreationAttempt) = StepView("err")

    override fun input(world: World, attempt: CreationAttempt, line: String): StepReply =
      error("step exploded")

    override fun summary(world: World, attempt: CreationAttempt): String? = null
  }

  private class TestCreationProvider(
    override val steps: List<CreationStep> = listOf(StatsStep(), SkippedStep())
  ) : CreationProvider {
    override val choices: List<CreationChoice> = emptyList()

    override fun available(world: World, choice: String, answers: Map<String, String>) =
      emptyList<String>()

    override fun validate(world: World, answers: Map<String, String>): String? = null

    override fun startRoom(world: World, answers: Map<String, String>): Label? = null

    override fun created(world: World, player: Player, newCharacter: Boolean) = Unit

    override fun prepare(world: World, player: Player, attempt: CreationAttempt) {
      if (attempt.state["fail_prepare"] == true) {
        error("prepare exploded")
      }
      val bonus = attempt.state["bonus_str"] as? Int ?: 0
      if (bonus > 0) {
        world.setBaseStat(player, "str", player.baseStat("str") + bonus)
      }
    }
  }

  private class Wiring(val provider: CreationProvider) : WorldPluginWiring {
    override val globals: Map<String, Any> = emptyMap()

    override fun install(world: World) {
      world.creation = provider
    }

    override fun started(world: World) {}
  }

  private fun run(
    files: Map<String, String>,
    wiring: WorldPluginWiring = Wiring(TestCreationProvider()),
  ): TestReport {
    val root = Files.createTempDirectory("gups-spawn-steps")
    for ((path, text) in TestWorlds.withDefaults(files)) {
      root.resolve(path).also { it.parent.createDirectories() }.writeText(text.trimIndent() + "\n")
    }
    return TestRunner(WorldDir(root), plugins = wiring).run(listOf("//zones/a"))
  }

  private val baseFiles =
    mapOf(
      "zones/a/package.star" to "package(api_version = 1, kind = \"zone\")",
      "zones/a/rooms.star" to "room('start')",
    )

  @Test
  fun `steps applies prepare values and without steps prepare does not run`() {
    val r =
      run(
        baseFiles +
          ("zones/a/a_test.star" to
            """
            def test_with_steps(t):
                w = t.world(seed = 1)
                p = w.spawn_player("Bob", at = ":start", steps = {"stats": ["str +2", "done"]})
                t.eq(p.base.str, 12)

            def test_without_steps(t):
                w = t.world(seed = 1)
                p = w.spawn_player("Bob", at = ":start")
                t.eq(p.base.str, 10)
            """
              .trimIndent())
      )
    assertEquals(2, r.passed, r.results.mapNotNull { it.message }.joinToString())
    assertEquals(0, r.failed)
    assertEquals(0, r.errors)
  }

  @Test
  fun `unknown or non-applicable step id fails`() {
    val r =
      run(
        baseFiles +
          ("zones/a/a_test.star" to
            """
            def test_unknown_step(t):
                w = t.world()
                w.spawn_player("Bob", at = ":start", steps = {"unknown": ["done"]})

            def test_skipped_step(t):
                w = t.world()
                w.spawn_player("Bob", at = ":start", steps = {"skip": ["done"]})
            """
              .trimIndent()),
        Wiring(TestCreationProvider(listOf(SkippedStep()))),
      )
    assertEquals(2, r.errors, r.results.mapNotNull { it.message }.joinToString())
    assertTrue(
      r.results.any {
        it.message?.contains("spawn_player(steps): step 'unknown' doesn't apply") == true
      }
    )
    assertTrue(
      r.results.any {
        it.message?.contains("spawn_player(steps): step 'skip' doesn't apply") == true
      }
    )
  }

  @Test
  fun `unfinished step fails`() {
    val r =
      run(
        baseFiles +
          ("zones/a/a_test.star" to
            """
            def test_unfinished_step(t):
                w = t.world()
                w.spawn_player("Bob", at = ":start", steps = {"stats": ["str +2"]})
            """
              .trimIndent()),
        Wiring(TestCreationProvider(listOf(StatsStep()))),
      )
    assertEquals(1, r.errors, r.results.mapNotNull { it.message }.joinToString())
    assertTrue(
      r.results.any {
        it.message?.contains("spawn_player(steps): step 'stats' isn't done after its lines") == true
      }
    )
  }

  @Test
  fun `throwing step fails`() {
    val r =
      run(
        baseFiles +
          ("zones/a/a_test.star" to
            """
            def test_throwing_step(t):
                w = t.world()
                w.spawn_player("Bob", at = ":start", steps = {"err": ["done"]})
            """
              .trimIndent()),
        Wiring(TestCreationProvider(listOf(ThrowsStep()))),
      )
    assertEquals(1, r.errors, r.results.mapNotNull { it.message }.joinToString())
    assertTrue(
      r.results.any {
        it.message?.contains("spawn_player(steps): step 'err' failed: step exploded") == true
      }
    )
  }

  @Test
  fun `throwing prepare fails`() {
    val r =
      run(
        baseFiles +
          ("zones/a/a_test.star" to
            """
            def test_throwing_prepare(t):
                w = t.world()
                w.spawn_player("Bob", at = ":start", steps = {"stats": ["fail_prepare", "done"]})
            """
              .trimIndent()),
        Wiring(TestCreationProvider(listOf(StatsStep()))),
      )
    assertEquals(1, r.errors, r.results.mapNotNull { it.message }.joinToString())
    assertTrue(
      r.results.any {
        it.message?.contains("spawn_player(steps): prepare failed: prepare exploded") == true
      }
    )
  }
}
