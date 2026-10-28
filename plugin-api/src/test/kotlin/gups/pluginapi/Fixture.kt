package gups.pluginapi

import gups.core.world.World
import gups.core.world.WorldConfig
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldPluginWiring
import gups.script.testing.TestWorlds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Writes [files] (paths relative to the world root) into a fresh temporary world. */
fun tempWorld(files: Map<String, String>): Path {
  val root = Files.createTempDirectory("gups-plugin-world")
  for ((path, text) in TestWorlds.withDefaults(files)) {
    val p = root.resolve(path)
    p.parent.createDirectories()
    p.writeText(text.trimIndent() + "\n")
  }
  return root
}

val ZONE =
  mapOf(
    "zones/z/package.star" to "package(api_version = 1, kind = \"zone\", title = \"Z\")",
    "zones/z/rooms.star" to
      """
        room("a", name = "Glade", desc = "A glade.", size = SIZE.large, regions = ["forest", "outdoors"], exits = [exit(DIR.north, ":b")])
        room("b", name = "Cave", desc = "A cave.", regions = ["underground"], flags = ["dead_end_ok"], exits = [exit("south", ":a")])
        mob("wolf", name = "a wolf", desc = "A wolf.", keywords = ["wolf"])
        item("stick", name = "a stick", desc = "A stick.", keywords = ["stick", "wood"])
    """,
  )

/** A headless world of [files], built as `TestCtx.world` builds one, with [plugins] wired in. */
fun world(
  files: Map<String, String> = ZONE,
  plugins: WorldPluginWiring = WorldPluginWiring.NONE,
  seed: Long = 7,
  features: gups.core.world.Features = gups.core.world.Features.ALL,
): World {
  val dir = WorldDir(tempWorld(files))
  val loader = ScriptLoader(dir, ScriptLimits.HARNESS, pluginGlobals = plugins.globals)
  val result = loader.load(dir.packages())
  check(result.ok) { result.report() }
  val host = StarlarkHost(result, ScriptLimits.HARNESS)
  val world =
    World(
      result.prototypes(),
      loader.schema,
      host.rules(),
      host,
      WorldConfig(seed = seed, checkInvariants = true, features = features),
    )
  plugins.install(world)
  host.install(world)
  world.fireOnLoad()
  plugins.started(world)
  return world
}
