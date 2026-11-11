package gups.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption
import gups.core.EngineInfo
import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.PluginLoader
import gups.script.WorldDir
import gups.script.WorldModule
import java.io.File
import java.nio.file.Path

/** Builds the `gups` command tree. */
fun gupsCommand(): CliktCommand =
  Gups()
    .subcommands(
      Serve(),
      TestCmd(),
      Lint(),
      Fuzz(),
      SnapshotCmd(),
      MigrateCheck(),
      Docs(),
      DescribeWorld(),
      Repro(),
      ScenarioCmd(),
      Backup(),
      Restore(),
      Deploy(),
      Invite(),
      ImportRoster(),
    )

class Gups : CliktCommand(name = "gups") {
  init {
    versionOption(
      EngineInfo.version,
      message = { "gups $it (api_version ${EngineInfo.API_VERSION})" },
    )
  }

  override fun help(context: Context) = "GUPS text MUD engine."

  override fun run() = Unit
}

enum class Profile {
  DEV,
  PROD,
}

/** The world's plugins; each warning goes to [log]. Close the host when done. */
fun loadPlugins(dir: WorldDir, module: WorldModule, log: (String) -> Unit): PluginHost =
  PluginLoader.load(dir, module, log = log)

/**
 * Runs [block] with [dir]'s plugins, printing their warnings. A `MODULE.gups` that doesn't read
 * means no plugins here; the command reports that error itself.
 */
internal fun <T> CliktCommand.withPlugins(dir: WorldDir, block: (PluginHost) -> T): T =
  loadPlugins(dir, WorldModule.read(dir).first) { echo("warning: $it", err = true) }.use(block)

/** `--db`, defaulting to the database of `--world`. */
internal fun dbPath(world: File?, db: File?): Path =
  db?.toPath() ?: GupsServer.defaultDb(WorldDir((world ?: File(".")).toPath()))
