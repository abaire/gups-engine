package gups.sample.heritage

import gups.core.Label
import gups.core.model.Mob
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.harness.FuzzWalker
import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.PluginLoader
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldMessages
import gups.script.WorldModule
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeritageWorldTest {
  private val hosts = mutableListOf<PluginHost>()

  @AfterTest fun close() = hosts.forEach { it.close() }

  /** A copy of the template world with `heritage.jar` pinned and [extra] files added. */
  private fun worldDir(extra: Map<String, String> = emptyMap()): WorldDir {
    val from = Path.of(System.getProperty("gups.templateWorld"))
    val root = Files.createTempDirectory("gups-heritage-world")
    Files.walk(from).use { paths ->
      for (p in paths) {
        val target = root.resolve(from.relativize(p).toString())
        if (Files.isDirectory(p)) target.createDirectories() else p.copyTo(target)
      }
    }
    val jar = root.resolve("plugins/heritage.jar").also { it.parent.createDirectories() }
    Path.of(System.getProperty("gups.sample.heritageJar")).copyTo(jar)
    val module = root.resolve("MODULE.gups")
    module.writeText(
      module
        .readText()
        .replaceFirst(
          "module(",
          "module(\n    plugins = {\"heritage\": \"${PluginLoader.sha256(jar)}\"},",
        )
    )
    for ((path, text) in extra) {
      val p = root.resolve(path)
      p.parent.createDirectories()
      p.writeText(text.trimIndent() + "\n")
    }
    return WorldDir(root)
  }

  private fun plugins(dir: WorldDir): PluginHost {
    val (module, error) = WorldModule.read(dir)
    assertNull(error)
    return PluginLoader.load(dir, module).also {
      hosts += it
      assertEquals(emptyList(), it.warnings)
    }
  }

  private data class BotState(
    val name: String,
    val choices: Map<String, String>,
    val room: String,
    val index: Int,
  )

  private fun walk(dir: WorldDir, seed: Long): Pair<FuzzWalker.Outcome, List<BotState>> {
    val outcome =
      FuzzWalker(dir, plugins(dir)).run(listOf("//zones/tutorial/..."), pulses = 300, seed = seed)
    assertTrue(outcome.ok, outcome.failureMessage)
    val bots =
      outcome.world!!.players.map {
        BotState(it.name, it.choices.toMap(), it.room.label.v, it.trackIndex)
      }
    return outcome to bots
  }

  @Test
  fun `the fuzz walker runs a world with heritage pinned, deterministically for a seed`() {
    val dir = worldDir()
    val (first, firstBots) = walk(dir, 7L)
    val (second, secondBots) = walk(dir, 7L)
    assertEquals(300, first.pulsesRun)
    assertTrue(firstBots.isNotEmpty())
    assertTrue(firstBots.all { "race" in it.choices && "class" in it.choices }, "$firstBots")
    assertEquals(firstBots, secondBots)
    assertEquals(first.botStartRooms, second.botStartRooms)
    assertEquals(first.world!!.pulse, second.world!!.pulse)
  }

  private val guildZone =
    mapOf(
      "zones/guilds/package.star" to """package(api_version = 1, kind = "zone")""",
      "zones/guilds/rooms.star" to
        """
        room("square", name = "Town Square", desc = "Central square.", exits = [exit("north", ":guild")])
        room("guild", name = "Mages Guild", desc = "A hall of magic.", tags = ["guild_mage"],
             exits = [exit("south", ":square")])
        room("barracks", name = "Barracks", desc = "Bunks.", flags = ["dead_end_ok"],
             spawns = [spawn(":mage")])
        mob("mage", name = "a mage", desc = "A mage.", keywords = ["mage"], traits = ["mage"],
            vitals = vitals(hp = 10, move = 100), behaviors = [wander(radius = 1, chance = 1)])
        mob("warrior", name = "a warrior", desc = "A warrior.", keywords = ["warrior"],
            traits = ["warrior"], vitals = vitals(hp = 10, move = 100),
            behaviors = [wander(radius = 1, chance = 1)])
        """,
    )

  /** The guild zone's world, built as the server builds one. */
  private fun guildWorld(): World {
    val dir = worldDir(guildZone)
    val plugins = plugins(dir)
    val (module, _) = WorldModule.read(dir)
    val loader = ScriptLoader(dir, ScriptLimits.HARNESS, plugins = plugins)
    val result = loader.load(listOf("zones/guilds"))
    assertTrue(result.ok, result.report())
    val host = StarlarkHost(result, ScriptLimits.HARNESS, loader)
    val world =
      World(
        result.prototypes(),
        loader.schema,
        host.rules(),
        host,
        module.config(WorldConfig(seed = 3, bubble = false), WorldMessages.read(dir).first),
      )
    plugins.install(world)
    host.install(world)
    world.start()
    plugins.started(world)
    return world
  }

  @Test
  fun `a mage mob wanders in and out of the mage guild while a warrior mob is kept out`() {
    val world = guildWorld()
    val square = world.room(Label("//zones/guilds:square"))
    val mage = world.spawnMob(Label("//zones/guilds:mage"), square)
    val warrior = world.spawnMob(Label("//zones/guilds:warrior"), square)
    assertTrue(mage.hasTrait("mage"))
    assertTrue(warrior.hasTrait("warrior"))
    val guild = Label("//zones/guilds:guild")
    val mageRooms = mutableSetOf<Label>()
    var mageMoves = 0
    var last = mage.room.label
    repeat(2_000) {
      world.runPulse()
      mageRooms += mage.room.label
      if (mage.room.label != last) mageMoves++
      last = mage.room.label
      assertTrue(warrior.room.label != guild, "the warrior got into the guild")
    }
    assertTrue(guild in mageRooms, "the mage never entered the guild")
    assertTrue(mageMoves >= 2, "the mage never left the guild again")
  }

  @Test
  fun `a mob spawned when the world is built carries its prototype traits`() {
    val world = guildWorld()
    val mage = world.actors.filterIsInstance<Mob>().single()
    assertEquals(listOf("mage"), mage.traits.map { it.name })
  }
}
