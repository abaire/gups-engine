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
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import gups.auth.AccountService
import gups.auth.DEFAULT_INVITE_DAYS
import gups.auth.parseDuration
import gups.auth.LoginSettings
import gups.auth.Roster
import gups.core.EngineInfo
import gups.core.Label
import gups.core.Role
import gups.harness.ReproRunner
import gups.harness.TestRunner
import gups.harness.render
import gups.persist.Backups
import gups.persist.Database
import gups.persist.ReproBundle
import gups.persist.SqliteAccountStore
import gups.persist.restoreBundle
import gups.script.ServerConfig
import gups.script.WorldDir
import gups.net.ServerTls
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/** Builds the `gups` command tree (SPEC §4.13). */
fun gupsCommand(): CliktCommand = Gups().subcommands(
    Serve(),
    TestCmd(),
    Lint(),
    Docs(),
    Repro(),
    Backup(),
    Restore(),
    Deploy(),
    Invite(),
    ImportRoster(),
)

/** Exit status of a command that is declared but not implemented yet. */
const val EXIT_NOT_IMPLEMENTED = 3

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

/** A subcommand that is declared with its final options but not implemented until [milestone]. */
abstract class StubCommand(name: String, private val summary: String, private val milestone: String) :
    CliktCommand(name = name) {
    override fun help(context: Context) = summary

    override fun run() {
        echo("gups $commandName: not implemented yet (planned for $milestone).", err = true)
        throw ProgramResult(EXIT_NOT_IMPLEMENTED)
    }
}

enum class Profile { DEV, PROD }

class Serve : CliktCommand(name = "serve") {
    val profile by mutuallyExclusiveOptions(
        option(help = "Single-user local development server.").switch("--dev" to Profile.DEV),
        option(help = "Shared course server.").switch("--prod" to Profile.PROD),
    ).single().required()
    val fresh by option("--fresh", help = "Ignore saved world state and boot from the .star definitions.").flag()
    val world by option("--world", help = "Course world directory (default: the current directory).").file(mustExist = true, canBeFile = false)
    val db by option("--db", help = "SQLite database file.").file(canBeDir = false)
    val port by option("--port", help = "--dev: telnet port on 127.0.0.1 (default 4000).").int()
    val start by option("--start", metavar = "LABEL", help = "Room new characters start in (default: MODULE.gups start_room, else //zones/tutorial:gate, else the first room).")
    val config by option("--config", metavar = "FILE", help = "--prod: server configuration file.").file(mustExist = true, canBeDir = false)
    val telnet by option("--telnet", metavar = "ADDR", help = "--prod: raw telnet address, e.g. :4000 or 127.0.0.1:4000 (default: off).")
    val telnetTls by option("--telnet-tls", metavar = "ADDR", help = "--prod: telnet over TLS address, e.g. :4443.")
    val https by option("--https", metavar = "ADDR", help = "--prod: HTTPS address for the web client and WebSocket, e.g. :443.")
    val tlsCert by option("--tls-cert", metavar = "FILE", help = "--prod: PEM certificate chain, e.g. fullchain.pem.").file(canBeDir = false)
    val tlsKey by option("--tls-key", metavar = "FILE", help = "--prod: PEM PKCS#8 private key, e.g. privkey.pem.").file(canBeDir = false)
    val selfSigned by option("--self-signed", help = "--prod: use a throwaway self-signed certificate, for local testing.").flag()
    val openSignup by option("--open-signup", help = "--prod: let new logins be made without an invite code.").nullableFlag("--no-open-signup")
    val maxCharacters by option("--max-characters", metavar = "N", help = "--prod: characters per account (default 5; instructors unlimited).").int()

    override fun help(context: Context) = "Run the server."

    override fun run() {
        val startLabel = start?.let { runCatching { Label(it) }.getOrNull() ?: throw UsageError("--start: '$it' is not a label") }
        val server = if (profile == Profile.PROD) {
            prodServer(startLabel)
        } else {
            if (config != null || telnet != null || telnetTls != null || https != null || tlsCert != null || tlsKey != null || selfSigned || openSignup != null || maxCharacters != null) {
                throw UsageError("--config, --telnet, --telnet-tls, --https, --tls-cert, --tls-key, --self-signed, --open-signup and --max-characters are for --prod")
            }
            val dir = WorldDir((world ?: File(".")).toPath())
            GupsServer(dir, port ?: 4000, startLabel, db?.toPath() ?: GupsServer.defaultDb(dir), fresh) { echo(it, err = true) }
        }
        if (!server.boot()) throw ProgramResult(1)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                server.stop()
                server.awaitStopped(SHUTDOWN_WAIT_MS)
            },
        )
        runBlocking {
            server.run { bound ->
                if (profile == Profile.PROD) {
                    val words = mapOf("telnet" to "raw telnet", "tls" to "telnet over TLS", "ws" to "HTTPS and the web client")
                    echo("GUPS server: " + server.ports.entries.joinToString(", ") { (t, p) -> "${words[t]} on port $p" } + ".")
                } else {
                    echo("GUPS dev server on 127.0.0.1:$bound. Connect with: telnet localhost $bound")
                }
            }
        }
    }

    /** `--prod` (M6 design §2.1, §3.5): the configuration file, with each flag overriding it (D-135). */
    private fun prodServer(startLabel: Label?): GupsServer {
        if (port != null) throw UsageError("--port is for --dev; use --telnet with --prod")
        val cfg = config?.let { f ->
            val (c, error) = ServerConfig.read(f.toPath())
            if (error != null) throw UsageError(error.render())
            c
        } ?: ServerConfig()
        cfg.unused().forEach { echo("warning: ${config?.name} sets $it, which this engine build doesn't use yet", err = true) }
        val dir = WorldDir((world?.toPath() ?: cfg.world?.let { Path.of(it) } ?: Path.of(".")))
        if (!dir.root.toFile().isDirectory) throw UsageError("no world directory at ${dir.root}")
        val raw = (telnet ?: cfg.telnet)?.let(::parseAddress)
        val secure = (telnetTls ?: cfg.telnetTls)?.let(::parseAddress)
        val web = (https ?: cfg.https)?.let(::parseAddress)
        if (raw == null && secure == null && web == null) {
            throw UsageError("--prod needs a listener: --telnet-tls :4443 and --https :443 with --tls-cert and --tls-key (or --self-signed), or --telnet :4000")
        }
        if (selfSigned && secure == null && web == null) throw UsageError("--self-signed needs --telnet-tls or --https")
        // One certificate serves both TLS listeners.
        val cert = if (secure != null || web != null) serverTls(cfg, (secure ?: web)!!.first) else null
        val tls = secure?.let { (host, port) -> TlsListener(host, port, cert!!) }
        val site = web?.let { (host, port) -> TlsListener(host, port, cert!!) }
        val login = LoginSettings(
            openSignup = openSignup ?: cfg.openSignup,
            maxCharacters = maxCharacters ?: cfg.maxCharacters,
            reservedNames = cfg.reservedNames.mapTo(HashSet()) { it.lowercase() },
        )
        if (login.maxCharacters < 1) throw UsageError("--max-characters must be at least 1")
        val dbPath = db?.toPath() ?: cfg.db?.let { Path.of(it) } ?: GupsServer.defaultDb(dir)
        cfg.restrictionLadder.firstOrNull { parseDuration(it).isFailure }?.let { throw UsageError("restriction_ladder: '$it' is not a length such as 1d, 7d or permanent") }
        if (cfg.restrictionLadder.isEmpty()) throw UsageError("restriction_ladder needs at least one step")
        return GupsServer(dir, raw?.second ?: 0, startLabel, dbPath, fresh, ProdOptions(raw?.first, login, cfg.restrictionLadder, tls, site)) { echo(it, err = true) }
    }

    /** The certificate: a throwaway self-signed one, or the PEM files of the flags or configuration (M6 design §5.1). */
    private fun serverTls(cfg: ServerConfig, host: String): ServerTls {
        if (selfSigned) {
            val dir = java.nio.file.Files.createTempDirectory("gups-self-signed")
            val hosts = listOf("localhost", "127.0.0.1") + listOf(host).filter { it != "0.0.0.0" && it != "::" && it !in setOf("localhost", "127.0.0.1") }
            echo("Using a throwaway self-signed certificate for ${hosts.joinToString()}; clients will warn about it.", err = true)
            return ServerTls.selfSigned(dir, hosts)
        }
        val cert = tlsCert?.toPath() ?: cfg.tlsCert?.let { Path.of(it) }
        val key = tlsKey?.toPath() ?: cfg.tlsKey?.let { Path.of(it) }
        if (cert == null || key == null) throw UsageError("TLS needs --tls-cert and --tls-key (or tls_cert and tls_key), or --self-signed")
        return runCatching { ServerTls(cert, key) }.getOrElse { throw UsageError("can't load the TLS certificate $cert and key $key: ${it.message}") }
    }
}

/** `host:port`, `[v6]:port` or `:port` (every interface) as a bind host and port. */
internal fun parseAddress(address: String): Pair<String, Int> {
    val colon = address.lastIndexOf(':')
    val port = address.substring(colon + 1).toIntOrNull()
    if (colon < 0 || port == null || port !in 0..65535) throw UsageError("'$address' is not an address such as :4000 or 127.0.0.1:4000")
    val host = address.substring(0, colon).removePrefix("[").removeSuffix("]").ifEmpty { "0.0.0.0" }
    return host to port
}

/** Exit status of `gups test` when a test fails or a pattern matches nothing. */
const val EXIT_TESTS_FAILED = 1

class TestCmd : CliktCommand(name = "test") {
    val patterns by argument("label-pattern").multiple(required = true)
    val world by option("--world", help = "Course world directory (default: the current directory).")
        .file(mustExist = true, canBeFile = false)

    override fun help(context: Context) = "Run *_test.star tests, e.g. //zones/goblin_caves/..."

    override fun run() {
        val dir = WorldDir((world ?: File(".")).toPath())
        var file: Label? = null
        val report = TestRunner(dir).run(patterns) { r ->
            if (r.file != file) {
                file = r.file
                echo(r.file.v)
            }
            echo(r.render())
        }
        for (p in report.problems) echo("gups test: $p", err = true)
        if (report.results.isNotEmpty()) echo("\n" + report.summary())
        if (!report.ok) throw ProgramResult(EXIT_TESTS_FAILED)
    }
}

class Lint : StubCommand("lint", "Run the static checks.", "M6") {
    val patterns by argument("label-pattern").multiple(required = true)
}

class Docs : StubCommand("docs", "Generate the API reference.", "M7") {
    val out by option("--out", help = "Output directory.").file(canBeFile = false)
}

/** How long a shutdown waits for the final checkpoint. */
const val SHUTDOWN_WAIT_MS = 30_000L

/** `--db`, defaulting to the database of `--world` (D-099). */
private fun dbPath(world: File?, db: File?): Path = db?.toPath() ?: GupsServer.defaultDb(WorldDir((world ?: File(".")).toPath()))

class Repro : CliktCommand(name = "repro") {
    val source by argument("diagnostic-id|bundle.json")
    val world by option("--world", help = "Course world directory (default: the current directory).").file(mustExist = true, canBeFile = false)
    val db by option("--db", help = "Database to read the diagnostic from (default: the world's).").file(canBeDir = false)
    val save by option("--save", help = "Also write the bundle to this file.").file(canBeDir = false)

    override fun help(context: Context) = "Replay a diagnostic in the harness."

    override fun run() {
        val json = source.toLongOrNull()?.let { id ->
            val path = dbPath(world, db)
            if (!path.toFile().isFile) throw UsageError("no database at $path")
            Database.open(path).use { d ->
                val row = d.diagnostic(id) ?: throw UsageError("no diagnostic $id in $path")
                row.reproJson ?: throw UsageError("diagnostic $id has no repro bundle (only script errors in a room have one)")
            }
        } ?: File(source).takeIf { it.isFile }?.readText() ?: throw UsageError("'$source' is neither a diagnostic id nor a bundle file")
        val bundle = runCatching { ReproBundle.parse(json) }.getOrElse { throw UsageError("not a repro bundle: ${it.message}") }
        save?.writeText(bundle.toJson())
        echo("Replaying ${bundle.hook ?: "the failure"} in ${bundle.room} (seed ${bundle.seed}, pulses ${bundle.checkpointPulse}..${bundle.pulse}, ${bundle.inputs.size} inputs)")
        val outcome = ReproRunner(WorldDir((world ?: File(".")).toPath())).run(
            seed = bundle.seed,
            startPulse = bundle.checkpointPulse,
            firstFreeId = bundle.maxId + 1,
            setup = { w -> restoreBundle(w, bundle) },
            inputs = bundle.records,
            untilPulse = bundle.pulse,
            matches = { it.hook == bundle.hook && it.message == bundle.message },
        )
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
    val world by option("--world", help = "Course world directory (default: the current directory).").file(mustExist = true, canBeFile = false)
    val db by option("--db", help = "Database to back up (default: the world's).").file(canBeDir = false)

    override fun help(context: Context) = "Back up the database."

    override fun run() {
        val path = dbPath(world, db)
        if (!path.toFile().isFile) throw UsageError("no database at $path")
        Database.open(path).use { echo("Backed up to ${Backups(it).snapshot(Backups.Kind.MANUAL)}") }
    }
}

class Restore : CliktCommand(name = "restore") {
    val file by argument("file").file(mustExist = true, canBeDir = false)
    val world by option("--world", help = "Course world directory (default: the current directory).").file(mustExist = true, canBeFile = false)
    val db by option("--db", help = "Database to overwrite (default: the world's).").file(canBeDir = false)

    override fun help(context: Context) = "Restore the database from a backup. Stop the server first."

    override fun run() {
        val path = dbPath(world, db)
        Database.restore(file.toPath(), path)
        echo("Restored $path from $file")
    }
}

class Deploy : StubCommand("deploy", "Deploy a course world commit (called by CI).", "M6") {
    val commit by option("--commit", metavar = "SHA", help = "Commit to deploy.").required()
}

/** Runs [block] on the account store of the world's database, written directly: the CLI tools work offline (D-134). */
private fun <T> withAccounts(world: File?, db: File?, block: (AccountService) -> T): T {
    val path = dbPath(world, db)
    return Database.open(path).use { d -> SqliteAccountStore(path, d::write).use { block(AccountService(it)) } }
}

private fun parseRole(text: String): Role =
    Role.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
        ?: throw UsageError("--role: '$text' is not one of ${Role.entries.joinToString { it.name.lowercase() }}")

class Invite : CliktCommand(name = "invite") {
    val role by option("--role", metavar = "R", help = "Role granted by the invite: player, builder, instructor or admin.").required()
    val days by option("--days", metavar = "N", help = "Days until the code expires (default $DEFAULT_INVITE_DAYS).").int().default(DEFAULT_INVITE_DAYS)
    val world by option("--world", help = "Course world directory (default: the current directory).").file(mustExist = true, canBeFile = false)
    val db by option("--db", help = "Database to add the invite to (default: the world's).").file(canBeDir = false)

    override fun help(context: Context) = "Create an invite code."

    override fun run() {
        val r = parseRole(role)
        if (days < 1) throw UsageError("--days must be at least 1")
        val code = withAccounts(world, db) { it.createInvite(r, days) }
        echo(code)
        echo("Invite for a new ${r.name.lowercase()} account; it works once and expires in $days days.", err = true)
    }
}

class ImportRoster : CliktCommand(name = "import-roster") {
    val roster by argument("roster.csv").file(mustExist = true, canBeDir = false)
    val days by option("--days", metavar = "N", help = "Days until the setup codes expire (default $DEFAULT_INVITE_DAYS).").int().default(DEFAULT_INVITE_DAYS)
    val world by option("--world", help = "Course world directory (default: the current directory).").file(mustExist = true, canBeFile = false)
    val db by option("--db", help = "Database to add the accounts to (default: the world's).").file(canBeDir = false)

    override fun help(context: Context) =
        "Create accounts from a roster CSV (columns login,github_handle,role) and print each new login's setup code."

    override fun run() {
        if (days < 1) throw UsageError("--days must be at least 1")
        val (entries, problems) = Roster.parse(roster.readText())
        if (problems.isNotEmpty()) {
            problems.forEach { echo("${roster.name}: $it", err = true) }
            throw ProgramResult(1)
        }
        val result = withAccounts(world, db) { it.importRoster(entries, days) }
        echo("login,setup_code")
        for ((login, code) in result.codes) echo("$login,$code")
        echo("${result.codes.size} accounts created, ${result.updated} updated, ${result.unchanged} unchanged. Setup codes expire in $days days.", err = true)
    }
}
