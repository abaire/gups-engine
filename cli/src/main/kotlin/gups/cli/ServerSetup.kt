package gups.cli

import com.github.ajalt.clikt.core.UsageError
import gups.auth.LoginSettings
import gups.auth.parseDuration
import gups.net.ServerTls
import gups.script.ServerConfig
import gups.script.WorldDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** `host:port`, `[v6]:port` or `:port` (every interface) as a bind host and port. */
internal fun parseAddress(address: String): Pair<String, Int> {
  val colon = address.lastIndexOf(':')
  val port = address.substring(colon + 1).toIntOrNull()?.takeIf { it in 0..65535 }
  if (colon < 0 || port == null)
    throw UsageError("'$address' is not an address such as :4000 or 127.0.0.1:4000")
  val host = address.substring(0, colon).removePrefix("[").removeSuffix("]").ifEmpty { "0.0.0.0" }
  return host to port
}

internal fun tlsListener(address: Pair<String, Int>?, cert: ServerTls?): TlsListener? =
  address?.let { (host, port) -> TlsListener(host, port, cert!!) }

/** The certificate in the PEM files [cert] and [key]. */
internal fun loadServerTls(cert: Path?, key: Path?): ServerTls {
  if (cert == null || key == null)
    throw UsageError(
      "TLS needs --tls-cert and --tls-key (or tls_cert and tls_key), or --self-signed"
    )
  return runCatching { ServerTls(cert, key) }
    .getOrElse {
      throw UsageError("can't load the TLS certificate $cert and key $key: ${it.message}")
    }
}

/** A throwaway certificate for [host]; the warning about it goes to [log]. */
internal fun selfSignedTls(host: String, log: (String) -> Unit): ServerTls {
  val dir = Files.createTempDirectory("gups-self-signed")
  val local = setOf("localhost", "127.0.0.1")
  val hosts = local.toList() + listOf(host).filter { it != "0.0.0.0" && it != "::" && it !in local }
  log(
    "Using a throwaway self-signed certificate for ${hosts.joinToString()}; clients will warn about it."
  )
  return ServerTls.selfSigned(dir, hosts)
}

internal fun checkRestrictionLadder(ladder: List<String>) {
  ladder
    .firstOrNull { parseDuration(it).isFailure }
    ?.let {
      throw UsageError("restriction_ladder: '$it' is not a length such as 1d, 7d or permanent")
    }
  if (ladder.isEmpty()) throw UsageError("restriction_ladder needs at least one step")
}

private val PORT_NAMES =
  mapOf(
    "telnet" to "raw telnet",
    "tls" to "telnet over TLS",
    "ws" to "HTTPS and the web client",
    "admin" to "the admin port",
  )

/** What the server prints once it is listening; [ports] maps each listener to its bound port. */
internal fun startupMessage(prod: Boolean, ports: Map<String, Int>, bound: Int): String {
  if (prod) {
    val listening = ports.entries.joinToString(", ") { (t, p) -> "${PORT_NAMES[t]} on port $p" }
    return "GUPS server: $listening."
  }
  val docs = ports["docs"]?.let { " API reference: http://127.0.0.1:$it/docs/" } ?: ""
  return "GUPS dev server on 127.0.0.1:$bound. Connect with: telnet localhost $bound.$docs"
}

/** The deploy settings of the flags, falling back to the configuration and the environment. */
internal fun deploySettings(
  cfg: ServerConfig,
  repository: String?,
  branch: String?,
  keyFile: File?,
): DeploySettings {
  val envKey = System.getenv("GUPS_DEPLOY_KEY_FILE")?.takeIf { it.isNotBlank() }?.let(::File)
  return DeploySettings(
    repository = repository ?: cfg.repository,
    branch = branch ?: cfg.deployBranch,
    keyFile = (keyFile ?: cfg.deployKeyFile?.let(::File) ?: envKey)?.toPath(),
  )
}

/**
 * The world directory of `--world`, falling back to the configuration and the current directory.
 */
internal fun prodWorldDir(world: File?, cfg: ServerConfig): WorldDir {
  val dir = WorldDir(world?.toPath() ?: cfg.world?.let { Path.of(it) } ?: Path.of("."))
  if (!dir.root.toFile().isDirectory) throw UsageError("no world directory at ${dir.root}")
  return dir
}

/** The login rules of the flags, falling back to the configuration. */
internal fun loginSettings(
  cfg: ServerConfig,
  openSignup: Boolean?,
  maxCharacters: Int?,
): LoginSettings {
  val login =
    LoginSettings(
      openSignup = openSignup ?: cfg.openSignup,
      maxCharacters = maxCharacters ?: cfg.maxCharacters,
      reservedNames = cfg.reservedNames.mapTo(HashSet()) { it.lowercase() },
    )
  if (login.maxCharacters < 1) throw UsageError("--max-characters must be at least 1")
  return login
}
