package gups.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import gups.auth.AccountService
import gups.auth.DEFAULT_INVITE_DAYS
import gups.auth.LoginSettings
import gups.auth.Roster
import gups.auth.parseDuration
import gups.core.EngineInfo
import gups.core.Label
import gups.core.Role
import gups.core.world.CommandRegistry
import gups.harness.FuzzWalker
import gups.harness.HarnessApi
import gups.harness.ReproRunner
import gups.harness.TestRunner
import gups.harness.render
import gups.net.ServerTls
import gups.net.toJson
import gups.persist.Backups
import gups.persist.Database
import gups.persist.ReproBundle
import gups.persist.SqliteAccountStore
import gups.persist.buildReproBundle
import gups.persist.restoreBundle
import gups.pluginapi.host.PluginHost
import gups.pluginapi.host.PluginInfo
import gups.pluginapi.host.PluginLoader
import gups.pluginapi.host.PluginMemberInfo
import gups.script.ApiDiff
import gups.script.Linter
import gups.script.LoadResult
import gups.script.MethodSpec
import gups.script.ParamSpec
import gups.script.ScriptApi
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.ServerConfig
import gups.script.WorldDir
import gups.script.WorldMessages
import gups.script.WorldModule
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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

class Serve : CliktCommand(name = "serve") {
  val profile by
    mutuallyExclusiveOptions(
        option(help = "Single-user local development server.").switch("--dev" to Profile.DEV),
        option(help = "Shared course server.").switch("--prod" to Profile.PROD),
      )
      .single()
      .required()
  val fresh by
    option("--fresh", help = "Ignore saved world state and boot from the .star definitions.").flag()
  val wipe by
    option(
        "--wipe",
        help =
          "Like --fresh, and also delete every character and item and record the world's current schema. Accounts are kept.",
      )
      .flag()
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val db by option("--db", help = "SQLite database file.").file(canBeDir = false)
  val port by option("--port", help = "--dev: telnet port on 127.0.0.1 (default 4000).").int()
  val start by
    option(
      "--start",
      metavar = "LABEL",
      help =
        "Room new characters start in (default: MODULE.gups start_room, else //zones/tutorial:gate, else the first room).",
    )
  val config by
    option("--config", metavar = "FILE", help = "--prod: server configuration file.")
      .file(mustExist = true, canBeDir = false)
  val telnet by
    option(
      "--telnet",
      metavar = "ADDR",
      help = "--prod: raw telnet address, e.g. :4000 or 127.0.0.1:4000 (default: off).",
    )
  val telnetTls by
    option("--telnet-tls", metavar = "ADDR", help = "--prod: telnet over TLS address, e.g. :4443.")
  val https by
    option(
      "--https",
      metavar = "ADDR",
      help = "--prod: HTTPS address for the web client and WebSocket, e.g. :443.",
    )
  val tlsCert by
    option(
        "--tls-cert",
        metavar = "FILE",
        help = "--prod: PEM certificate chain, e.g. fullchain.pem.",
      )
      .file(canBeDir = false)
  val tlsKey by
    option(
        "--tls-key",
        metavar = "FILE",
        help = "--prod: PEM PKCS#8 private key, e.g. privkey.pem.",
      )
      .file(canBeDir = false)
  val selfSigned by
    option(
        "--self-signed",
        help = "--prod: use a throwaway self-signed certificate, for local testing.",
      )
      .flag()
  val adminAddress by
    option(
      "--admin",
      metavar = "ADDR",
      help = "--prod: admin port address (default $DEFAULT_ADMIN).",
    )
  val adminTokenFile by
    option(
        "--admin-token-file",
        metavar = "FILE",
        help = "--prod: file holding the admin token (default: \$GUPS_ADMIN_TOKEN_FILE).",
      )
      .file(canBeDir = false)
  val repository by
    option(
      "--repository",
      metavar = "URL",
      help = "--prod: course repository to deploy from (default: the world's origin).",
    )
  val deployBranch by
    option(
      "--deploy-branch",
      metavar = "BRANCH",
      help = "--prod: branch deploys come from (default main).",
    )
  val deployKeyFile by
    option(
        "--deploy-key-file",
        metavar = "FILE",
        help = "--prod: SSH key for fetching (default: \$GUPS_DEPLOY_KEY_FILE).",
      )
      .file(canBeDir = false)
  val forceCommit by
    option(
        "--force-commit",
        help = "--prod: start even if the world isn't at the commit the database was saved at.",
      )
      .flag()
  val openSignup by
    option("--open-signup", help = "--prod: let new logins be made without an invite code.")
      .nullableFlag("--no-open-signup")
  val docsPort by
    option(
        "--docs-port",
        metavar = "PORT",
        help = "--dev: HTTP port for /docs/ on 127.0.0.1 (default 4080).",
      )
      .int()
  val maxCharacters by
    option(
        "--max-characters",
        metavar = "N",
        help = "--prod: characters per account (default 5; instructors unlimited).",
      )
      .int()
  val shutdownTimeout by
    option(
        "--shutdown-timeout",
        metavar = "SECONDS",
        help = "--prod: how long shutdown waits for the final checkpoint (default 30).",
      )
      .int()

  override fun help(context: Context) = "Run the server."

  override fun run() {
    val startLabel =
      start?.let {
        runCatching { Label(it) }.getOrNull() ?: throw UsageError("--start: '$it' is not a label")
      }
    val server =
      if (profile == Profile.PROD) {
        prodServer(startLabel)
      } else {
        val prodOnly =
          listOf(
            config,
            telnet,
            telnetTls,
            https,
            tlsCert,
            tlsKey,
            openSignup,
            maxCharacters,
            adminAddress,
            adminTokenFile,
            repository,
            deployBranch,
            deployKeyFile,
            shutdownTimeout,
          )
        if (prodOnly.any { it != null } || selfSigned || forceCommit) {
          throw UsageError(
            "--config and the listener, certificate, account, admin and deploy options are for --prod"
          )
        }
        val dir = WorldDir((world ?: File(".")).toPath())
        GupsServer(
          dir,
          port ?: 4000,
          startLabel,
          db?.toPath() ?: GupsServer.defaultDb(dir),
          fresh,
          docsPort = docsPort ?: 4080,
          wipe = wipe,
        ) {
          echo(it, err = true)
        }
      }
    if (!server.boot()) throw ProgramResult(1)
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          server.stop()
          server.awaitStopped()
        }
      )
    runBlocking {
      server.run { bound ->
        if (profile == Profile.PROD) {
          val words =
            mapOf(
              "telnet" to "raw telnet",
              "tls" to "telnet over TLS",
              "ws" to "HTTPS and the web client",
              "admin" to "the admin port",
            )
          echo(
            "GUPS server: " +
              server.ports.entries.joinToString(", ") { (t, p) -> "${words[t]} on port $p" } +
              "."
          )
        } else {
          val docs =
            server.ports["docs"]?.let { " API reference: http://127.0.0.1:$it/docs/" } ?: ""
          echo("GUPS dev server on 127.0.0.1:$bound. Connect with: telnet localhost $bound.$docs")
        }
      }
    }
  }

  /** `--prod`: the configuration file, with each flag overriding it. */
  private fun prodServer(startLabel: Label?): GupsServer {
    if (port != null) throw UsageError("--port is for --dev; use --telnet with --prod")
    if (docsPort != null)
      throw UsageError("--docs-port is for --dev; --prod serves /docs/ on --https")
    val cfg =
      config?.let { f ->
        val (c, error) = ServerConfig.read(f.toPath())
        if (error != null) throw UsageError(error.render())
        c
      } ?: ServerConfig()
    val dir = WorldDir((world?.toPath() ?: cfg.world?.let { Path.of(it) } ?: Path.of(".")))
    if (!dir.root.toFile().isDirectory) throw UsageError("no world directory at ${dir.root}")
    val raw = (telnet ?: cfg.telnet)?.let(::parseAddress)
    val secure = (telnetTls ?: cfg.telnetTls)?.let(::parseAddress)
    val web = (https ?: cfg.https)?.let(::parseAddress)
    if (raw == null && secure == null && web == null) {
      throw UsageError(
        "--prod needs a listener: --telnet-tls :4443 and --https :443 with --tls-cert and --tls-key (or --self-signed), or --telnet :4000"
      )
    }
    if (selfSigned && secure == null && web == null)
      throw UsageError("--self-signed needs --telnet-tls or --https")
    // One certificate serves both TLS listeners.
    val cert = if (secure != null || web != null) serverTls(cfg, (secure ?: web)!!.first) else null
    val tls = secure?.let { (host, port) -> TlsListener(host, port, cert!!) }
    val site = web?.let { (host, port) -> TlsListener(host, port, cert!!) }
    val login =
      LoginSettings(
        openSignup = openSignup ?: cfg.openSignup,
        maxCharacters = maxCharacters ?: cfg.maxCharacters,
        reservedNames = cfg.reservedNames.mapTo(HashSet()) { it.lowercase() },
      )
    if (login.maxCharacters < 1) throw UsageError("--max-characters must be at least 1")
    val dbPath = db?.toPath() ?: cfg.db?.let { Path.of(it) } ?: GupsServer.defaultDb(dir)
    cfg.restrictionLadder
      .firstOrNull { parseDuration(it).isFailure }
      ?.let {
        throw UsageError("restriction_ladder: '$it' is not a length such as 1d, 7d or permanent")
      }
    if (cfg.restrictionLadder.isEmpty())
      throw UsageError("restriction_ladder needs at least one step")
    val timeout = shutdownTimeout ?: cfg.shutdownTimeout
    if (timeout < 1) throw UsageError("--shutdown-timeout must be at least 1")
    val (adminHost, adminPort) = parseAddress(adminAddress ?: cfg.admin ?: DEFAULT_ADMIN)
    val token =
      readSecret(adminTokenFile ?: cfg.adminTokenFile?.let(::File), "GUPS_ADMIN_TOKEN_FILE")
    if (token == null)
      echo(
        "warning: no admin token (admin_token_file or GUPS_ADMIN_TOKEN_FILE), so the admin port serves only /healthz",
        err = true,
      )
    val deploy =
      DeploySettings(
        repository = repository ?: cfg.repository,
        branch = deployBranch ?: cfg.deployBranch,
        keyFile =
          (deployKeyFile
              ?: cfg.deployKeyFile?.let(::File)
              ?: System.getenv("GUPS_DEPLOY_KEY_FILE")?.takeIf { it.isNotBlank() }?.let(::File))
            ?.toPath(),
      )
    return GupsServer(
      dir,
      raw?.second ?: 0,
      startLabel,
      dbPath,
      fresh,
      ProdOptions(
        raw?.first,
        login,
        cfg.restrictionLadder,
        tls,
        site,
        AdminListener(adminHost, adminPort, token),
        deploy,
        forceCommit,
        timeout * 1000L,
      ),
      wipe = wipe,
    ) {
      echo(it, err = true)
    }
  }

  /**
   * The certificate: a throwaway self-signed one, or the PEM files of the flags or configuration.
   */
  private fun serverTls(cfg: ServerConfig, host: String): ServerTls {
    if (selfSigned) {
      val dir = java.nio.file.Files.createTempDirectory("gups-self-signed")
      val hosts =
        listOf("localhost", "127.0.0.1") +
          listOf(host).filter {
            it != "0.0.0.0" && it != "::" && it !in setOf("localhost", "127.0.0.1")
          }
      echo(
        "Using a throwaway self-signed certificate for ${hosts.joinToString()}; clients will warn about it.",
        err = true,
      )
      return ServerTls.selfSigned(dir, hosts)
    }
    val cert = tlsCert?.toPath() ?: cfg.tlsCert?.let { Path.of(it) }
    val key = tlsKey?.toPath() ?: cfg.tlsKey?.let { Path.of(it) }
    if (cert == null || key == null)
      throw UsageError(
        "TLS needs --tls-cert and --tls-key (or tls_cert and tls_key), or --self-signed"
      )
    return runCatching { ServerTls(cert, key) }
      .getOrElse {
        throw UsageError("can't load the TLS certificate $cert and key $key: ${it.message}")
      }
  }
}

/** `host:port`, `[v6]:port` or `:port` (every interface) as a bind host and port. */
internal fun parseAddress(address: String): Pair<String, Int> {
  val colon = address.lastIndexOf(':')
  val port = address.substring(colon + 1).toIntOrNull()
  if (colon < 0 || port == null || port !in 0..65535)
    throw UsageError("'$address' is not an address such as :4000 or 127.0.0.1:4000")
  val host = address.substring(0, colon).removePrefix("[").removeSuffix("]").ifEmpty { "0.0.0.0" }
  return host to port
}

/** Exit status of `gups test` when a test fails or a pattern matches nothing. */
const val EXIT_TESTS_FAILED = 1

class TestCmd : CliktCommand(name = "test") {
  val patterns by argument("label-pattern").multiple(required = true)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)

  override fun help(context: Context) = "Run *_test.star tests, e.g. //zones/goblin_caves/..."

  override fun run() {
    val dir = WorldDir((world ?: File(".")).toPath())
    var file: Label? = null
    val report =
      withPlugins(dir) { plugins ->
        TestRunner(dir, plugins).run(patterns) { r ->
          if (r.file != file) {
            file = r.file
            echo(r.file.v)
          }
          echo(r.render())
        }
      }
    for (p in report.problems) echo("gups test: $p", err = true)
    if (report.results.isNotEmpty()) echo("\n" + report.summary())
    if (!report.ok) throw ProgramResult(EXIT_TESTS_FAILED)
  }
}

class ScenarioCmd : CliktCommand(name = "scenario") {
  val file by argument("file.star").file(mustExist = true, canBeDir = false)
  val pkg by
    option(
        "--package",
        metavar = "//PKG",
        help = "Package the file runs as if it were in, e.g. //zones/tutorial.",
      )
      .required()
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)

  override fun help(context: Context) =
    "Run a test file kept outside the world and print what its players typed and received."

  override fun run() {
    val dir = WorldDir((world ?: File(".")).toPath())
    val report =
      withPlugins(dir) { plugins ->
        TestRunner(dir, plugins).scenario(file.toPath(), pkg) { r ->
          echo(r.render(transcript = true))
        }
      }
    for (p in report.problems) echo("gups scenario: $p", err = true)
    if (report.results.isNotEmpty()) echo("\n" + report.summary())
    if (!report.ok) throw ProgramResult(EXIT_TESTS_FAILED)
  }
}

class Lint : CliktCommand(name = "lint") {
  val patterns by argument("label-pattern").multiple(required = false)
  val base by
    option(
      "--base",
      metavar = "REF",
      help = "Base git ref to compare prototype declarations against.",
    )
  val format by
    option("--format", help = "Output format: text or github.")
      .choice("text", "github")
      .default("text")
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)

  override fun help(context: Context) = "Run static checks on course world packages (gate 2)."

  override fun run() =
    withPlugins(WorldDir((world ?: File(".")).toPath())) { plugins -> lint(plugins) }

  private fun lint(plugins: PluginHost) {
    val dir = WorldDir((world ?: File(".")).toPath())
    val patternList = patterns.ifEmpty { listOf("//...") }

    val basePrototypes =
      base?.let { ref ->
        val git = Git(dir.root)
        if (!git.isWorkTree())
          throw UsageError("--base requires the world to be a git working tree")
        val commit =
          try {
            git.commit(ref)
          } catch (e: GitException) {
            throw UsageError("failed to load base commit '$ref': ${e.message}")
          }
        val staging = Files.createTempDirectory("gups-lint-base")
        try {
          git.run("worktree", "add", "--detach", "--quiet", staging.toString(), commit)
          val baseDir = WorldDir(staging)
          val baseLoader =
            ScriptLoader(
              baseDir,
              ScriptLimits(),
              pluginGlobals = plugins.globals,
              knownGmcp = { plugins.gmcpPackages },
            )
          val baseResult = baseLoader.load(baseDir.packages())
          if (!baseResult.ok) {
            val errs = baseResult.errors.joinToString("\n") { it.render() }
            throw UsageError("failed to load base commit '$ref':\n$errs")
          }
          baseResult.prototypes()
        } finally {
          runCatching { git.run("worktree", "remove", "--force", staging.toString()) }
          runCatching { Files.deleteIfExists(staging) }
        }
      }

    val report = Linter(dir, basePrototypes, plugins).lint(patternList)

    if (format == "github") {
      for (f in report.findings) echo(f.renderGithub())
    } else {
      for (f in report.findings) echo(f.renderText())
      if (report.findings.isNotEmpty()) echo("\n" + report.summary())
    }

    System.getenv("GITHUB_STEP_SUMMARY")
      ?.takeIf { it.isNotBlank() }
      ?.let { path -> runCatching { File(path).appendText(report.renderMarkdown()) } }

    if (!report.ok) throw ProgramResult(1)
  }
}

class Fuzz : CliktCommand(name = "fuzz") {
  val patterns by argument("label-pattern").multiple(required = true)
  val pulses by
    option("--pulses", metavar = "N", help = "Number of pulses to run (default 10000).")
      .int()
      .default(10000)
  val seed by
    option("--seed", metavar = "S", help = "Random seed (default 42).").long().default(42L)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)

  override fun help(context: Context) = "Run the fuzz walker against changed zones (gate 4)."

  override fun run() {
    val dir = WorldDir((world ?: File(".")).toPath())
    val outcome =
      withPlugins(dir) { plugins ->
        FuzzWalker(dir, plugins).run(patterns, pulses, seed) { p ->
          if (pulses >= 2000) echo("... pulse $p / $pulses")
        }
      }
    if (!outcome.ok) {
      echo("gups fuzz failed: ${outcome.failureMessage}", err = true)
      val failPulse = outcome.failedPulse ?: outcome.pulsesRun.toLong()
      echo(
        "Failed at pulse $failPulse. Reproduce with: gups fuzz --seed $seed --pulses $failPulse ${patterns.joinToString(" ")}"
      )
      val w = outcome.world
      val d = outcome.diagnostic
      if (d != null && w != null) {
        val bundle = buildReproBundle(w, d, outcome.botStartRooms)
        echo("Repro bundle:")
        echo(bundle.toJson())
      }
      throw ProgramResult(1)
    }
    echo(
      "Fuzz walker completed ${outcome.pulsesRun} pulses with ${outcome.bots} bots across ${outcome.zones.size} zones. 0 errors."
    )
  }
}

class SnapshotCmd : CliktCommand(name = "snapshot") {
  val sanitized by
    option(
        "--sanitized",
        help =
          "Required: strip accounts, tokens, invites, restrictions, diagnostics and the item audit, and rename characters.",
      )
      .flag()
  val out by
    option("--out", metavar = "FILE", help = "Output database file.")
      .file(canBeDir = false)
      .required()
  val admin by
    option(
      "--admin",
      metavar = "ADDR",
      help = "Download from a running server's admin port, e.g. $DEFAULT_ADMIN.",
    )
  val adminTokenFile by
    option(
        "--admin-token-file",
        metavar = "FILE",
        help = "File holding the admin token (default: \$GUPS_ADMIN_TOKEN_FILE).",
      )
      .file(canBeDir = false)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val db by
    option("--db", help = "Database to read directly when offline (default: the world's).")
      .file(canBeDir = false)

  override fun help(context: Context) =
    "Make a sanitized snapshot of the world database for migrations (gate 5)."

  override fun run() {
    // Only sanitized snapshots exist; the flag makes that explicit at every call.
    if (!sanitized)
      throw UsageError(
        "only sanitized snapshots are supported: run gups snapshot --sanitized --out FILE"
      )
    admin?.let { address ->
      val status = AdminClient(address, adminTokenFile).download("/snapshot", out)
      if (status != 200)
        throw UsageError("failed to download snapshot from admin port (HTTP $status)")
      echo("Sanitized snapshot saved to ${out.path} from admin port.")
      return
    }
    val path = dbPath(world, db)
    if (!path.toFile().isFile) throw UsageError("no database at $path")
    try {
      Database.sanitizedCopy(path, out.toPath())
    } catch (e: Exception) {
      throw UsageError("failed to create snapshot from $path: ${e.message}")
    }
    echo("Sanitized snapshot saved to ${out.path}.")
  }
}

class MigrateCheck : CliktCommand(name = "migrate-check") {
  val snapshot by
    option("--snapshot", metavar = "FILE", help = "Sanitized production snapshot database file.")
      .file(canBeDir = false)
      .required()
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)

  override fun help(context: Context) =
    "Check migrations and reconciliation against a production snapshot (gate 5)."

  override fun run() {
    if (!snapshot.isFile) {
      echo("warning: no snapshot file at ${snapshot.path}; skipping migration check", err = true)
      return
    }
    val tempDb = Files.createTempFile("gups-migrate-check", ".db")
    try {
      Files.copy(snapshot.toPath(), tempDb, StandardCopyOption.REPLACE_EXISTING)
      val dir = WorldDir((world ?: File(".")).toPath())
      val outcome = withPlugins(dir) { MigrationChecker(dir, tempDb, it).check() }
      val ageStr = outcome.snapshotAgeText ?: "unknown age"
      echo("Checking migrations against snapshot ${snapshot.name} ($ageStr)...")
      if (!outcome.ok) {
        for (p in outcome.problems) echo("error: $p", err = true)
        throw ProgramResult(1)
      }
      echo(
        "Migration check passed: ${outcome.entitiesChecked} entities, ${outcome.charactersChecked} characters, ${outcome.versionUpgrades} version upgrades, ${outcome.expectedTombstones} expected tombstones."
      )
    } finally {
      runCatching { Files.deleteIfExists(tempDb) }
    }
  }
}

class Docs : CliktCommand(name = "docs") {
  val json by option("--json", help = "Write the machine-readable API model (api.json).").flag()
  val out by
    option("--out", help = "Output directory (default build/docs), or with --json a .json file.")
      .file()
  val world by
    option("--world", help = "Course world directory; include its plugins.")
      .file(mustExist = true, canBeFile = false)
  val updateBaseline by
    option("--update-baseline", help = "Before a release: write docs/api/api-N.json.").flag()
  val docsDir by
    option(
        "--docs-dir",
        metavar = "DIR",
        help = "The engine's docs directory, for --update-baseline (default: docs).",
      )
      .file(canBeFile = false)
      .default(File("docs"))

  override fun help(context: Context) = "Generate the API reference."

  override fun run() {
    if (updateBaseline) {
      if (json || out != null || world != null)
        throw UsageError("--update-baseline takes no --json, --out or --world")
      updateBaseline()
      return
    }
    if (json) {
      if (world != null)
        throw UsageError("--world is for the HTML reference; api.json never includes plugins")
      writeJson()
      return
    }
    val dir = out ?: File("build/docs")
    if (dir.isFile || dir.name.endsWith(".json"))
      throw UsageError("--out: give a directory for the HTML reference, not ${dir.name}")
    var spec: WorldDocSpec? = null
    val plugins =
      world
        ?.let { w ->
          val worldDir = WorldDir(w.toPath())
          val (module, error) = WorldModule.read(worldDir)
          if (error != null) throw UsageError("--world: ${error.render()}")
          module.problems().firstOrNull()?.let { throw UsageError("--world: $it") }
          val (messages, messagesError) = WorldMessages.read(worldDir)
          if (messagesError != null) throw UsageError("--world: ${messagesError.render()}")
          messages.problems().firstOrNull()?.let { throw UsageError("--world: $it") }
          loadPlugins(worldDir, module) { echo("warning: $it", err = true) }
            .use { host ->
              val result =
                ScriptLoader(worldDir, ScriptLimits(), plugins = host).load(worldDir.packages())
              spec = worldDocSpec(module, messages, scriptGmcpPackages(result))
              host.describe().map(::toDocSpec)
            }
        }
        .orEmpty()
    val files =
      DocsRenderer.render(ScriptApi.model(HarnessApi.specs()), plugins = plugins, world = spec)
    dir.mkdirs()
    for ((name, text) in files) dir.resolve(name).writeText(text, Charsets.UTF_8)
    echo("Wrote the API reference (api_version ${EngineInfo.API_VERSION}) to $dir")
  }

  /** Freezes the current API as the baseline of api_version N. */
  private fun updateBaseline() {
    val n = EngineInfo.API_VERSION
    val file = docsDir.resolve("api").also { it.mkdirs() }.resolve("api-$n.json")
    file.writeText(ApiDiff.normalize(ScriptApi.model(HarnessApi.specs()).toJson()), Charsets.UTF_8)
    echo("Wrote $file")
  }

  private fun writeJson() {
    val text = ScriptApi.model(HarnessApi.specs()).toJson()
    val dest = out
    if (dest == null) {
      echo(text, trailingNewline = false)
      return
    }
    // A .json path is the file itself; anything else is a directory.
    val file =
      when {
        dest.name.endsWith(".json") -> dest.also { it.absoluteFile.parentFile?.mkdirs() }
        dest.isFile -> throw UsageError("--out: $dest is a file; give a directory or a .json path")
        '.' in dest.name ->
          throw UsageError("--out: give a directory or a .json path, not ${dest.name}")
        else -> dest.also { it.mkdirs() }.resolve("api.json")
      }
    file.writeText(text, Charsets.UTF_8)
  }
}

/** The world's plugins; each warning goes to [log]. Close the host when done. */
fun loadPlugins(dir: WorldDir, module: WorldModule, log: (String) -> Unit): PluginHost =
  PluginLoader.load(dir, module, log = log)

/**
 * Runs [block] with [dir]'s plugins, printing their warnings. A `MODULE.gups` that doesn't read
 * means no plugins here; the command reports that error itself.
 */
private fun <T> CliktCommand.withPlugins(dir: WorldDir, block: (PluginHost) -> T): T =
  loadPlugins(dir, WorldModule.read(dir).first) { echo("warning: $it", err = true) }.use(block)

/** What `plugins.html` shows about a plugin. */
internal fun toDocSpec(p: PluginInfo): PluginDocSpec {
  fun method(m: PluginMemberInfo) =
    MethodSpec(
      m.name,
      m.doc,
      m.params.map {
        ParamSpec(
          it.name,
          positional = true,
          named = true,
          default = if (it.required) null else it.defaultValue.toString(),
          doc = it.doc,
        )
      },
    )
  return PluginDocSpec(
    id = p.id,
    doc = p.doc,
    builtins = p.builtins.map(::method),
    effects = p.effects.map(::method),
    commands =
      p.commands.map {
        PluginCommandDocSpec(it.name, it.lane.name.lowercase(), it.role.name.lowercase(), it.doc)
      },
    gmcp = p.gmcp.map { PluginGmcpDocSpec(it.pkg, it.doc, it.kind) },
    hooks = p.hooks.map { PluginHookDocSpec(it.name, it.doc) },
    traits =
      p.traits.map {
        PluginTraitDocSpec(
          it.name,
          it.label,
          it.doc,
          it.statDeltas,
          it.maxVitalDeltas,
          it.resistDeltas,
          it.flags,
          it.modifiers,
        )
      },
    restrictions = p.restrictions.map { PluginRestrictionDocSpec(it.point, it.doc) },
    containmentFilters = p.containmentFilters.map { PluginFilterDocSpec(it.doc) },
    ownedBehaviors = p.ownedBehaviors.map { PluginOwnedBehaviorsDocSpec(it.doc) },
    dispositionModifiers = p.dispositionModifiers.map { PluginDispositionModifierDocSpec(it.doc) },
    factions = p.factions.map { PluginFactionAdjusterDocSpec(it.doc) },
    choices =
      p.choices.map { c ->
        PluginChoiceDocSpec(
          c.id,
          c.title,
          c.prompt,
          c.options.map { o -> PluginOptionDocSpec(o.id, o.label, o.summary, o.help, o.traits) },
        )
      },
  )
}

/** How long a shutdown waits for the final checkpoint by default (`shutdown_timeout`). */
const val SHUTDOWN_WAIT_MS = 30_000L

/** `--db`, defaulting to the database of `--world`. */
private fun dbPath(world: File?, db: File?): Path =
  db?.toPath() ?: GupsServer.defaultDb(WorldDir((world ?: File(".")).toPath()))

class Repro : CliktCommand(name = "repro") {
  val source by argument("diagnostic-id|bundle.json")
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val db by
    option("--db", help = "Database to read the diagnostic from (default: the world's).")
      .file(canBeDir = false)
  val save by option("--save", help = "Also write the bundle to this file.").file(canBeDir = false)

  override fun help(context: Context) = "Replay a diagnostic in the harness."

  override fun run() {
    val json =
      source.toLongOrNull()?.let { id ->
        val path = dbPath(world, db)
        if (!path.toFile().isFile) throw UsageError("no database at $path")
        Database.open(path).use { d ->
          val row = d.diagnostic(id) ?: throw UsageError("no diagnostic $id in $path")
          row.reproJson
            ?: throw UsageError(
              "diagnostic $id has no repro bundle (only script errors in a room have one)"
            )
        }
      }
        ?: File(source).takeIf { it.isFile }?.readText()
        ?: throw UsageError("'$source' is neither a diagnostic id nor a bundle file")
    val bundle =
      runCatching { ReproBundle.parse(json) }
        .getOrElse { throw UsageError("not a repro bundle: ${it.message}") }
    save?.writeText(bundle.toJson())
    echo(
      "Replaying ${bundle.hook ?: "the failure"} in ${bundle.room} (seed ${bundle.seed}, pulses ${bundle.checkpointPulse}..${bundle.pulse}, ${bundle.inputs.size} inputs)"
    )
    val reproDir = WorldDir((world ?: File(".")).toPath())
    val outcome =
      withPlugins(reproDir) { plugins ->
        ReproRunner(reproDir, plugins)
          .run(
            seed = bundle.seed,
            startPulse = bundle.checkpointPulse,
            firstFreeId = bundle.maxId + 1,
            setup = { w -> restoreBundle(w, bundle) },
            inputs = bundle.records,
            untilPulse = bundle.pulse,
            matches = { it.hook == bundle.hook && it.message == bundle.message },
          )
      }
    outcome.notes.forEach { echo("  note: $it") }
    if (outcome.reproduced) {
      val d = outcome.matching.first()
      echo("Reproduced at pulse ${d.pulse}: ${d.message}")
      d.stack?.let { echo(it) }
    } else {
      echo("Not reproduced. The replay logged ${outcome.diagnostics.size} other diagnostics:")
      outcome.diagnostics.forEach { echo("  [${it.kind}] ${it.message}") }
      throw ProgramResult(1)
    }
  }
}

class Backup : CliktCommand(name = "backup") {
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val db by
    option("--db", help = "Database to back up (default: the world's).").file(canBeDir = false)

  override fun help(context: Context) = "Back up the database."

  override fun run() {
    val path = dbPath(world, db)
    if (!path.toFile().isFile) throw UsageError("no database at $path")
    Database.open(path).use { echo("Backed up to ${Backups(it).snapshot(Backups.Kind.MANUAL)}") }
  }
}

class Restore : CliktCommand(name = "restore") {
  val file by argument("file").file(mustExist = true, canBeDir = false)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val db by
    option("--db", help = "Database to overwrite (default: the world's).").file(canBeDir = false)

  override fun help(context: Context) = "Restore the database from a backup. Stop the server first."

  override fun run() {
    val path = dbPath(world, db)
    Database.restore(file.toPath(), path)
    echo("Restored $path from $file")
  }
}

class Deploy : CliktCommand(name = "deploy") {
  val commit by
    option(
      "--commit",
      metavar = "SHA",
      help = "Commit to deploy (default: the tip of the deploy branch).",
    )
  val admin by
    option("--admin", metavar = "ADDR", help = "The server's admin port (default $DEFAULT_ADMIN).")
      .default(DEFAULT_ADMIN)
  val adminTokenFile by
    option(
        "--admin-token-file",
        metavar = "FILE",
        help = "File holding the admin token (default: \$GUPS_ADMIN_TOKEN_FILE).",
      )
      .file(canBeDir = false)

  override fun help(context: Context) =
    "Deploy a course world commit to a running server (called by CI)."

  override fun run() {
    val client = AdminClient(admin, adminTokenFile)
    var ok = false
    // The response is NDJSON: {"progress"} lines, then {"ok", "commit", "packages", "errors"}.
    val status =
      client.post("/deploy", toJson(mapOf("commit" to commit)).toString(), "application/json") {
        _,
        line ->
        val o = runCatching { Json.parseToJsonElement(line) as JsonObject }.getOrNull()
        fun text(key: String) = (o?.get(key) as? JsonPrimitive)?.content
        when {
          text("progress") != null -> echo(text("progress"))
          o?.containsKey("ok") == true -> {
            ok = text("ok") == "true"
            val errors =
              (o["errors"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
            if (ok) echo("Deployed ${text("commit")}.")
            else errors.forEach { echo("error: $it", err = true) }
          }
          else -> echo(line, err = true)
        }
      }
    if (status != 200 || !ok) throw ProgramResult(1)
  }
}

/**
 * Runs [block] on the account store of the world's database, written directly: the CLI tools work
 * offline.
 */
private fun <T> withAccounts(world: File?, db: File?, block: (AccountService) -> T): T {
  val path = dbPath(world, db)
  return Database.open(path).use { d ->
    SqliteAccountStore(path, d::write).use { block(AccountService(it)) }
  }
}

private fun parseRole(text: String): Role =
  Role.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
    ?: throw UsageError(
      "--role: '$text' is not one of ${Role.entries.joinToString { it.name.lowercase() }}"
    )

class Invite : CliktCommand(name = "invite") {
  val role by
    option(
        "--role",
        metavar = "R",
        help = "Role granted by the invite: player, builder, instructor or admin.",
      )
      .required()
  val days by
    option(
        "--days",
        metavar = "N",
        help = "Days until the code expires (default $DEFAULT_INVITE_DAYS).",
      )
      .int()
      .default(DEFAULT_INVITE_DAYS)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val db by
    option("--db", help = "Database to add the invite to (default: the world's).")
      .file(canBeDir = false)

  override fun help(context: Context) = "Create an invite code."

  override fun run() {
    val r = parseRole(role)
    if (days < 1) throw UsageError("--days must be at least 1")
    val code = withAccounts(world, db) { it.createInvite(r, days) }
    echo(code)
    echo(
      "Invite for a new ${r.name.lowercase()} account; it works once and expires in $days days.",
      err = true,
    )
  }
}

class ImportRoster : CliktCommand(name = "import-roster") {
  val roster by argument("roster.csv").file(mustExist = true, canBeDir = false)
  val days by
    option(
        "--days",
        metavar = "N",
        help = "Days until the setup codes expire (default $DEFAULT_INVITE_DAYS).",
      )
      .int()
      .default(DEFAULT_INVITE_DAYS)
  val world by
    option("--world", help = "Course world directory (default: the current directory).")
      .file(mustExist = true, canBeFile = false)
  val db by
    option("--db", help = "Database to add the accounts to (default: the world's).")
      .file(canBeDir = false)
  val admin by
    option(
      "--admin",
      metavar = "ADDR",
      help = "Import through a running server's admin port instead, e.g. $DEFAULT_ADMIN.",
    )
  val adminTokenFile by
    option(
        "--admin-token-file",
        metavar = "FILE",
        help = "File holding the admin token (default: \$GUPS_ADMIN_TOKEN_FILE).",
      )
      .file(canBeDir = false)

  override fun help(context: Context) =
    "Create accounts from a roster CSV (columns login,github_handle,role,disabled) and print each new login's setup code."

  override fun run() {
    if (days < 1) throw UsageError("--days must be at least 1")
    admin?.let { address ->
      if (days != DEFAULT_INVITE_DAYS)
        throw UsageError("--days works only offline; the admin port uses $DEFAULT_INVITE_DAYS days")
      val status =
        AdminClient(address, adminTokenFile).post("/roster", roster.readText(), "text/csv") {
          status,
          line ->
          echo(line, err = status != 200)
        }
      if (status != 200) throw ProgramResult(1)
      return
    }
    val (entries, problems) = Roster.parse(roster.readText())
    if (problems.isNotEmpty()) {
      problems.forEach { echo("${roster.name}: $it", err = true) }
      throw ProgramResult(1)
    }
    val result = withAccounts(world, db) { it.importRoster(entries, days) }
    echo("login,setup_code")
    for ((login, code) in result.codes) echo("$login,$code")
    echo(
      "${result.created} accounts created, ${result.updated} updated, ${result.unchanged} unchanged, ${result.disabled.size} disabled, ${result.enabled.size} enabled. Setup codes expire in $days days.",
      err = true,
    )
    if (result.disabled.isNotEmpty()) {
      echo(
        "Warning: characters already in the world were not quit; use --admin against a running server.",
        err = true,
      )
    }
  }
}

/** GMCP packages declared by the packages that loaded, for `world.html`. */
internal fun scriptGmcpPackages(result: LoadResult): List<ScriptGmcpDocSpec> =
  result.packages
    .filterKeys { it !in result.failed }
    .values
    .flatMap { it.gmcpPackages }
    .map { ScriptGmcpDocSpec(it.name, it.doc, it.pkg) }

/** What `world.html` and the feature filter of `gups docs --world` need from a world. */
fun worldDocSpec(
  module: WorldModule,
  messages: WorldMessages,
  gmcp: List<ScriptGmcpDocSpec> = emptyList(),
): WorldDocSpec =
  WorldDocSpec(
    module.schema,
    module.features,
    module.rename,
    CommandRegistry.describe(module.features, module.rename),
    messages.texts,
    gmcp,
  )
