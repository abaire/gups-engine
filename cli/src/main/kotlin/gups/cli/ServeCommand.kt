package gups.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.nullableFlag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import gups.core.Label
import gups.net.ServerTls
import gups.script.ServerConfig
import gups.script.WorldDir
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** How long a shutdown waits for the final checkpoint by default (`shutdown_timeout`). */
const val SHUTDOWN_WAIT_MS = 30_000L

/** The addresses `--prod` listens on; each is null when that listener is off. */
private class Listeners(val raw: Pair<String, Int>?, val tls: TlsListener?, val site: TlsListener?)

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
    val server = if (profile == Profile.PROD) prodServer(startLabel) else devServer(startLabel)
    if (!server.boot()) throw ProgramResult(1)
    Runtime.getRuntime()
      .addShutdownHook(
        Thread {
          server.stop()
          server.awaitStopped()
        }
      )
    runBlocking {
      server.run { bound -> echo(startupMessage(profile == Profile.PROD, server.ports, bound)) }
    }
  }

  /** `--dev`: a single-user server on the loopback interface. */
  private fun devServer(startLabel: Label?): GupsServer {
    rejectProdOnlyOptions()
    val dir = WorldDir((world ?: File(".")).toPath())
    return GupsServer(
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

  private fun rejectProdOnlyOptions() {
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
    val anyGiven = prodOnly.any { it != null } || selfSigned || forceCommit
    if (anyGiven) {
      throw UsageError(
        "--config and the listener, certificate, account, admin and deploy options are for --prod"
      )
    }
  }

  /** `--prod`: the configuration file, with each flag overriding it. */
  private fun prodServer(startLabel: Label?): GupsServer {
    rejectDevOnlyOptions()
    val cfg = readConfig()
    val dir = prodWorldDir(world, cfg)
    val listeners = prodListeners(cfg)
    val login = loginSettings(cfg, openSignup, maxCharacters)
    checkRestrictionLadder(cfg.restrictionLadder)
    val timeout = shutdownSeconds(cfg)
    return GupsServer(
      dir,
      listeners.raw?.second ?: 0,
      startLabel,
      prodDb(cfg, dir),
      fresh,
      ProdOptions(
        listeners.raw?.first,
        login,
        cfg.restrictionLadder,
        listeners.tls,
        listeners.site,
        adminListener(cfg),
        deploySettings(cfg, repository, deployBranch, deployKeyFile),
        forceCommit,
        timeout * 1000L,
      ),
      wipe = wipe,
    ) {
      echo(it, err = true)
    }
  }

  private fun rejectDevOnlyOptions() {
    if (port != null) throw UsageError("--port is for --dev; use --telnet with --prod")
    if (docsPort != null)
      throw UsageError("--docs-port is for --dev; --prod serves /docs/ on --https")
  }

  private fun prodDb(cfg: ServerConfig, dir: WorldDir): Path =
    db?.toPath() ?: cfg.db?.let { Path.of(it) } ?: GupsServer.defaultDb(dir)

  private fun shutdownSeconds(cfg: ServerConfig): Int {
    val seconds = shutdownTimeout ?: cfg.shutdownTimeout
    if (seconds < 1) throw UsageError("--shutdown-timeout must be at least 1")
    return seconds
  }

  private fun readConfig(): ServerConfig {
    val file = config ?: return ServerConfig()
    val (cfg, error) = ServerConfig.read(file.toPath())
    if (error != null) throw UsageError(error.render())
    return cfg
  }

  private fun prodListeners(cfg: ServerConfig): Listeners {
    val raw = address(telnet, cfg.telnet)
    val secure = address(telnetTls, cfg.telnetTls)
    val web = address(https, cfg.https)
    checkListeners(hasRaw = raw != null, hasTls = secure != null || web != null)
    // One certificate serves both TLS listeners.
    val cert = (secure ?: web)?.let { serverTls(cfg, it.first) }
    return Listeners(raw, tlsListener(secure, cert), tlsListener(web, cert))
  }

  private fun address(flag: String?, configured: String?) =
    (flag ?: configured)?.let(::parseAddress)

  private fun checkListeners(hasRaw: Boolean, hasTls: Boolean) {
    if (!hasRaw && !hasTls) {
      throw UsageError(
        "--prod needs a listener: --telnet-tls :4443 and --https :443 with --tls-cert and --tls-key (or --self-signed), or --telnet :4000"
      )
    }
    if (selfSigned && !hasTls) throw UsageError("--self-signed needs --telnet-tls or --https")
  }

  private fun adminListener(cfg: ServerConfig): AdminListener {
    val (host, port) = parseAddress(adminAddress ?: cfg.admin ?: DEFAULT_ADMIN)
    val token =
      readSecret(adminTokenFile ?: cfg.adminTokenFile?.let(::File), "GUPS_ADMIN_TOKEN_FILE")
    if (token == null)
      echo(
        "warning: no admin token (admin_token_file or GUPS_ADMIN_TOKEN_FILE), so the admin port serves only /healthz",
        err = true,
      )
    return AdminListener(host, port, token)
  }

  private fun serverTls(cfg: ServerConfig, host: String): ServerTls {
    if (selfSigned) return selfSignedTls(host) { echo(it, err = true) }
    return loadServerTls(
      tlsCert?.toPath() ?: cfg.tlsCert?.let { Path.of(it) },
      tlsKey?.toPath() ?: cfg.tlsKey?.let { Path.of(it) },
    )
  }
}
