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
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import gups.core.EngineInfo
import gups.core.Label
import gups.harness.ReproRunner
import gups.harness.TestRunner
import gups.harness.render
import gups.persist.Backups
import gups.persist.Database
import gups.persist.ReproBundle
import gups.persist.restoreBundle
import gups.script.WorldDir
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
    val port by option("--port", help = "Telnet port (--dev: 4000).").int().default(4000)
    val start by option("--start", metavar = "LABEL", help = "Room new characters start in (--dev: //zones/tutorial:gate, else the first room).")

    override fun help(context: Context) = "Run the server."

    override fun run() {
        if (profile == Profile.PROD) {
            echo("gups serve --prod: not implemented yet (planned for M6).", err = true)
            throw ProgramResult(EXIT_NOT_IMPLEMENTED)
        }
        val startLabel = start?.let { runCatching { Label(it) }.getOrNull() ?: throw UsageError("--start: '$it' is not a label") }
        val dir = WorldDir((world ?: File(".")).toPath())
        val server = DevServer(dir, port, startLabel, db?.toPath() ?: DevServer.defaultDb(dir), fresh) { echo(it, err = true) }
        if (!server.boot()) throw ProgramResult(1)
        Runtime.getRuntime().addShutdownHook(
            Thread {
                server.stop()
                server.awaitStopped(SHUTDOWN_WAIT_MS)
            },
        )
        runBlocking {
            server.run { bound -> echo("GUPS dev server on 127.0.0.1:$bound. Connect with: telnet localhost $bound") }
        }
    }
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
private fun dbPath(world: File?, db: File?): Path = db?.toPath() ?: DevServer.defaultDb(WorldDir((world ?: File(".")).toPath()))

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

class Invite : StubCommand("invite", "Create an invite code.", "M6") {
    val role by option("--role", metavar = "R", help = "Role granted by the invite.").required()
}

class ImportRoster : StubCommand("import-roster", "Create accounts from a roster CSV.", "M6") {
    val roster by argument("roster.csv").file(mustExist = true, canBeDir = false)
}
