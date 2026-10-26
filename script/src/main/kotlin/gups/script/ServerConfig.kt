package gups.script

import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Starlark

/**
 * The server configuration of `gups serve --prod --config FILE`: Starlark with one `server(...)`
 * call, like `MODULE.gups`. It holds this machine's paths and settings, so it lives outside the
 * course world. Secrets never go in it, only the paths of files holding them. Addresses are
 * `host:port` or `:port`. A CLI flag overrides each setting.
 */
data class ServerConfig(
  val world: String? = null,
  val db: String? = null,
  /** Raw telnet; null is off. */
  val telnet: String? = null,
  val telnetTls: String? = null,
  val https: String? = null,
  val admin: String? = null,
  val tlsCert: String? = null,
  val tlsKey: String? = null,
  val adminTokenFile: String? = null,
  val repository: String? = null,
  val deployBranch: String = "main",
  val deployKeyFile: String? = null,
  val openSignup: Boolean = false,
  val maxCharacters: Int = 5,
  val restrictionLadder: List<String> = listOf("1d", "7d", "permanent"),
  val reservedNames: List<String> = emptyList(),
  /** Seconds a shutdown waits for the final checkpoint to commit. */
  val shutdownTimeout: Int = 30,
) {
  companion object {
    val SPEC =
      DeclSpec(
        name = "server",
        doc = "Declares this server's settings in server.gups.",
        params =
          listOf(
            DeclParam("world", "str", "Path to the course world directory.", default = "None"),
            DeclParam("db", "str", "Path to the SQLite database file.", default = "None"),
            DeclParam(
              "telnet",
              "str",
              "Raw telnet listen address (host:port or :port), or None to disable.",
              default = "None",
            ),
            DeclParam(
              "telnet_tls",
              "str",
              "TLS telnet listen address (host:port or :port).",
              default = "None",
            ),
            DeclParam(
              "https",
              "str",
              "HTTPS and WebSocket listen address (host:port or :port).",
              default = "None",
            ),
            DeclParam(
              "admin",
              "str",
              "Loopback admin HTTP listen address (host:port).",
              default = "None",
            ),
            DeclParam("tls_cert", "str", "Path to the TLS certificate PEM file.", default = "None"),
            DeclParam("tls_key", "str", "Path to the TLS private key PEM file.", default = "None"),
            DeclParam(
              "admin_token_file",
              "str",
              "Path to the file containing the admin bearer token.",
              default = "None",
            ),
            DeclParam(
              "repository",
              "str",
              "Git repository URL or path for deploys.",
              default = "None",
            ),
            DeclParam(
              "deploy_branch",
              "str",
              "Git branch deployed by the admin endpoint.",
              default = "'main'",
            ),
            DeclParam(
              "deploy_key_file",
              "str",
              "Path to the SSH deploy key file.",
              default = "None",
            ),
            DeclParam(
              "open_signup",
              "bool",
              "True to allow creating player accounts without an invite code.",
              default = "False",
            ),
            DeclParam("max_characters", "int", "Maximum characters per account.", default = "5"),
            DeclParam(
              "restriction_ladder",
              "list[str]",
              "Default restriction durations for @restrict.",
              default = "['1d', '7d', 'permanent']",
            ),
            DeclParam(
              "reserved_names",
              "list[str]",
              "Character names players may not register.",
              default = "[]",
            ),
            DeclParam(
              "shutdown_timeout",
              "int",
              "Seconds a shutdown waits for the final checkpoint to be saved.",
              default = "30",
            ),
          ),
      )

    /** Reads [path]. Errors are [LoadError]s naming the file. */
    fun read(path: Path): Pair<ServerConfig, LoadError?> {
      if (!path.isRegularFile())
        return ServerConfig() to LoadError("", "config", "no server configuration at $path")
      val reader = Reader()
      execSettings(path, path.name, "config", reader)?.let {
        return ServerConfig() to it
      }
      val c =
        reader.result
          ?: return ServerConfig() to
            LoadError("", "config", "${path.name} must call server(...)", path.name)
      return c to null
    }
  }

  /** The `server()` builtin: called exactly once. */
  private class Reader {
    var result: ServerConfig? = null

    @StarlarkMethod(
      name = "server",
      doc = "Declares this server's settings.",
      extraKeywords = Param(name = "kwargs"),
    )
    fun server(kwargs: Dict<String, Any>): Any {
      if (result != null) fail("server() is called more than once")
      return SPEC.bind(kwargs = kwargs) { d ->
        val max = d.int("max_characters") ?: 5
        if (max < 1) fail("server(max_characters): must be at least 1")
        val timeout = d.int("shutdown_timeout") ?: 30
        if (timeout < 1) fail("server(shutdown_timeout): must be at least 1")
        result =
          ServerConfig(
            world = d.str("world"),
            db = d.str("db"),
            telnet = d.str("telnet"),
            telnetTls = d.str("telnet_tls"),
            https = d.str("https"),
            admin = d.str("admin"),
            tlsCert = d.str("tls_cert"),
            tlsKey = d.str("tls_key"),
            adminTokenFile = d.str("admin_token_file"),
            repository = d.str("repository"),
            deployBranch = d.str("deploy_branch") ?: "main",
            deployKeyFile = d.str("deploy_key_file"),
            openSignup = d.bool("open_signup") ?: false,
            maxCharacters = max,
            restrictionLadder =
              d["restriction_ladder"]?.let { d.strList("restriction_ladder") }
                ?: listOf("1d", "7d", "permanent"),
            reservedNames = d["reserved_names"]?.let { d.strList("reserved_names") } ?: emptyList(),
            shutdownTimeout = timeout,
          )
        Starlark.NONE
      }
    }
  }
}
