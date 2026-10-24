package gups.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.groups.mutuallyExclusiveOptions
import com.github.ajalt.clikt.parameters.groups.required
import com.github.ajalt.clikt.parameters.groups.single
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.switch
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.file
import gups.core.EngineInfo
import gups.core.Label
import gups.harness.TestRunner
import gups.harness.render
import gups.script.WorldDir
import java.io.File

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

class Serve : StubCommand("serve", "Run the server.", "M3") {
    val profile by mutuallyExclusiveOptions(
        option(help = "Single-user local development server.").switch("--dev" to Profile.DEV),
        option(help = "Shared course server.").switch("--prod" to Profile.PROD),
    ).single().required()
    val fresh by option("--fresh", help = "Ignore saved world state and boot from the .star definitions.").flag()
    val world by option("--world", help = "Course world directory.").file(mustExist = true, canBeFile = false)
    val db by option("--db", help = "SQLite database file.").file(canBeDir = false)
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

class Repro : StubCommand("repro", "Replay a diagnostic in the harness.", "M4") {
    val source by argument("diagnostic-id|bundle.json")
}

class Backup : StubCommand("backup", "Back up the database.", "M4")

class Restore : StubCommand("restore", "Restore the database from a backup.", "M4") {
    val file by argument("file").file(mustExist = true, canBeDir = false)
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
