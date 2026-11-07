package gups.harness

import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FuzzWalkerTest {

  @Test
  fun `fuzz walker runs on tutorial zone and passes`() {
    val templateWorld = System.getProperty("gups.templateWorld") ?: "../template-world"
    val dir = WorldDir(Path.of(templateWorld))
    val walker = FuzzWalker(dir)
    val outcome = walker.run(listOf("//zones/tutorial/..."), pulses = 50, seed = 12345L)
    assertTrue(outcome.ok, outcome.failureMessage)
    assertEquals(50, outcome.pulsesRun)
    assertEquals(1, outcome.bots)
    assertEquals(listOf("zones/tutorial"), outcome.zones)
  }

  @Test
  fun `fuzz walker fails when unknown zone is targeted`() {
    val templateWorld = System.getProperty("gups.templateWorld") ?: "../template-world"
    val dir = WorldDir(Path.of(templateWorld))
    val walker = FuzzWalker(dir)
    val outcome = walker.run(listOf("//zones/unknown/..."), pulses = 10)
    assertFalse(outcome.ok)
    assertNotNull(outcome.failureMessage)
  }

  @Test
  fun `fuzz walker respawns bot cleanly if it leaves the world`() {
    val root = java.nio.file.Files.createTempDirectory("gups-fuzz-respawn")
    val zoneDir = root.resolve("zones/z").also { java.nio.file.Files.createDirectories(it) }
    java.nio.file.Files.writeString(
      zoneDir.resolve("package.star"),
      "package(api_version = 1, kind = \"zone\")\n",
    )
    java.nio.file.Files.writeString(
      zoneDir.resolve("rooms.star"),
      """
      def _noop(ctx): pass
      room("r", name = "R", flags = ["dead_end_ok"], exits = [], commands = {"quit": _noop})
      """
        .trimIndent(),
    )
    for ((path, text) in TestWorlds.withDefaults(emptyMap())) {
      root
        .resolve(path)
        .also { java.nio.file.Files.createDirectories(it.parent) }
        .toFile()
        .writeText(text)
    }
    val walker = FuzzWalker(WorldDir(root))
    val outcome = walker.run(listOf("//zones/z/..."), pulses = 30, seed = 42L)
    assertTrue(outcome.ok, outcome.failureMessage)
  }

  @Test
  fun `fuzz walker refuses a world without MODULE gups`() {
    val root = java.nio.file.Files.createTempDirectory("gups-fuzz-bare")
    val outcome = FuzzWalker(WorldDir(root)).run(listOf("//..."), pulses = 5)
    assertFalse(outcome.ok)
    assertTrue("MODULE.gups is required" in outcome.failureMessage.orEmpty())
  }

  private class StepWiring(
    val step: gups.core.model.CreationStep,
    val onPrepare: (gups.core.model.Player) -> Unit = {},
  ) : gups.script.WorldPluginWiring {
    override val globals: Map<String, Any> = emptyMap()

    override fun started(world: gups.core.world.World) {}

    override fun install(world: gups.core.world.World) {
      world.creation =
        object : gups.core.model.CreationProvider {
          override val choices = emptyList<gups.core.model.CreationChoice>()
          override val steps = listOf(step)

          override fun available(
            world: gups.core.world.World,
            choice: String,
            answers: Map<String, String>,
          ) = emptyList<String>()

          override fun validate(
            world: gups.core.world.World,
            answers: Map<String, String>,
          ): String? = null

          override fun startRoom(
            world: gups.core.world.World,
            answers: Map<String, String>,
          ): gups.core.Label? = null

          override fun created(
            world: gups.core.world.World,
            player: gups.core.model.Player,
            newCharacter: Boolean,
          ) = Unit

          override fun prepare(
            world: gups.core.world.World,
            player: gups.core.model.Player,
            attempt: gups.core.model.CreationAttempt,
          ) {
            onPrepare(player)
          }
        }
    }
  }

  @Test
  fun `fuzz walker bots run custom steps with done and execute prepare`() {
    val root = java.nio.file.Files.createTempDirectory("gups-fuzz-steps")
    val zoneDir = root.resolve("zones/z").also { java.nio.file.Files.createDirectories(it) }
    java.nio.file.Files.writeString(
      zoneDir.resolve("package.star"),
      "package(api_version = 1, kind = \"zone\")\n",
    )
    java.nio.file.Files.writeString(
      zoneDir.resolve("rooms.star"),
      "room(\"r\", name = \"R\", flags = [\"dead_end_ok\"], exits = [])\n",
    )
    for ((path, text) in TestWorlds.withDefaults(emptyMap())) {
      root
        .resolve(path)
        .also { java.nio.file.Files.createDirectories(it.parent) }
        .toFile()
        .writeText(text)
    }

    var stepRan = false
    val step =
      object : gups.core.model.CreationStep {
        override val id = "boost"
        override val title = "Boost"

        override fun applies(
          world: gups.core.world.World,
          attempt: gups.core.model.CreationAttempt,
        ) = true

        override fun show(world: gups.core.world.World, attempt: gups.core.model.CreationAttempt) =
          gups.core.model.StepView("boost")

        override fun input(
          world: gups.core.world.World,
          attempt: gups.core.model.CreationAttempt,
          line: String,
        ): gups.core.model.StepReply {
          if (line == "done") {
            stepRan = true
            attempt.state["boosted"] = true
            return gups.core.model.StepReply.Done
          }
          return gups.core.model.StepReply.Stay("type done")
        }

        override fun summary(
          world: gups.core.world.World,
          attempt: gups.core.model.CreationAttempt,
        ): String? = null
      }

    val wiring = StepWiring(step) { player -> player.baseStats["str"] = 18 }

    val outcome =
      FuzzWalker(WorldDir(root), wiring).run(listOf("//zones/z/..."), pulses = 5, seed = 42L)
    assertTrue(outcome.ok, outcome.failureMessage)
    assertTrue(stepRan)
    val bot = outcome.world!!.players.first()
    assertEquals(18, bot.baseStat("str"))
  }

  @Test
  fun `fuzz walker fails when creation step throws`() {
    val root = java.nio.file.Files.createTempDirectory("gups-fuzz-step-fail")
    val zoneDir = root.resolve("zones/z").also { java.nio.file.Files.createDirectories(it) }
    java.nio.file.Files.writeString(
      zoneDir.resolve("package.star"),
      "package(api_version = 1, kind = \"zone\")\n",
    )
    java.nio.file.Files.writeString(
      zoneDir.resolve("rooms.star"),
      "room(\"r\", name = \"R\", flags = [\"dead_end_ok\"], exits = [])\n",
    )
    for ((path, text) in TestWorlds.withDefaults(emptyMap())) {
      root
        .resolve(path)
        .also { java.nio.file.Files.createDirectories(it.parent) }
        .toFile()
        .writeText(text)
    }

    val throwingStep =
      object : gups.core.model.CreationStep {
        override val id = "bad_step"
        override val title = "Bad"

        override fun applies(
          world: gups.core.world.World,
          attempt: gups.core.model.CreationAttempt,
        ) = true

        override fun show(world: gups.core.world.World, attempt: gups.core.model.CreationAttempt) =
          gups.core.model.StepView("bad")

        override fun input(
          world: gups.core.world.World,
          attempt: gups.core.model.CreationAttempt,
          line: String,
        ): gups.core.model.StepReply = error("step blew up")

        override fun summary(
          world: gups.core.world.World,
          attempt: gups.core.model.CreationAttempt,
        ): String? = null
      }

    val outcome =
      FuzzWalker(WorldDir(root), StepWiring(throwingStep)).run(listOf("//zones/z/..."), pulses = 5)
    assertFalse(outcome.ok)
    assertTrue(
      outcome.failureMessage?.contains("bad_step") == true,
      "Expected step name in '${outcome.failureMessage}'",
    )
  }
}
