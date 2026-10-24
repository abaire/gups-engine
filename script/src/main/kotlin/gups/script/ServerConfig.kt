package gups.script

import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.Dict
import net.starlark.java.eval.Starlark
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The server configuration of `gups serve --prod --config FILE` (M6 design §2.1): Starlark with
 * one `server(...)` call, like `MODULE.gups`. It holds this machine's paths and settings, so it
 * lives outside the course world. Secrets never go in it, only the paths of files holding
 * them. Addresses are `host:port` or `:port`. A CLI flag overrides each setting.
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
) {
    companion object {
        /** Reads [path]. Errors are [LoadError]s naming the file. */
        fun read(path: Path): Pair<ServerConfig, LoadError?> {
            if (!path.isRegularFile()) return ServerConfig() to LoadError("", "config", "no server configuration at $path")
            val reader = Reader()
            execSettings(path, path.name, "config", reader)?.let { return ServerConfig() to it }
            val c = reader.result ?: return ServerConfig() to LoadError("", "config", "${path.name} must call server(...)", path.name)
            return c to null
        }
    }

    /** The `server()` builtin: called exactly once. */
    private class Reader {
        var result: ServerConfig? = null

        @StarlarkMethod(name = "server", doc = "Declares this server's settings.", extraKeywords = Param(name = "kwargs"))
        fun server(kwargs: Dict<String, Any>): Any {
            if (result != null) fail("server() is called more than once")
            val kw = Kw("server", kwargs)
            fun s(name: String) = kw[name]?.let { str(it, kw.what(name)) }
            val max = kw["max_characters"]?.let { int(it, kw.what("max_characters")) } ?: 5
            if (max < 1) fail("server(max_characters): must be at least 1")
            result = ServerConfig(
                world = s("world"),
                db = s("db"),
                telnet = s("telnet"),
                telnetTls = s("telnet_tls"),
                https = s("https"),
                admin = s("admin"),
                tlsCert = s("tls_cert"),
                tlsKey = s("tls_key"),
                adminTokenFile = s("admin_token_file"),
                repository = s("repository"),
                deployBranch = s("deploy_branch") ?: "main",
                deployKeyFile = s("deploy_key_file"),
                openSignup = kw["open_signup"]?.let { bool(it, kw.what("open_signup")) } ?: false,
                maxCharacters = max,
                restrictionLadder = kw["restriction_ladder"]?.let { strList(it, kw.what("restriction_ladder")) } ?: listOf("1d", "7d", "permanent"),
                reservedNames = kw["reserved_names"]?.let { strList(it, kw.what("reserved_names")) } ?: emptyList(),
            )
            kw.finish()
            return Starlark.NONE
        }
    }
}
