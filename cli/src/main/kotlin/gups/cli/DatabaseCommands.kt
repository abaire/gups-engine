package gups.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import gups.harness.ReproRunner
import gups.net.toJson
import gups.persist.Backups
import gups.persist.Database
import gups.persist.ReproBundle
import gups.persist.restoreBundle
import gups.script.WorldDir
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    val address = admin
    if (address != null) downloadSnapshot(address) else copySnapshot()
  }

  private fun downloadSnapshot(address: String) {
    val status = AdminClient(address, adminTokenFile).download("/snapshot", out)
    if (status != 200)
      throw UsageError("failed to download snapshot from admin port (HTTP $status)")
    echo("Sanitized snapshot saved to ${out.path} from admin port.")
  }

  private fun copySnapshot() {
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
    val bundle =
      runCatching { ReproBundle.parse(bundleJson()) }
        .getOrElse { throw UsageError("not a repro bundle: ${it.message}") }
    save?.writeText(bundle.toJson())
    echo(
      "Replaying ${bundle.hook ?: "the failure"} in ${bundle.room} (seed ${bundle.seed}, pulses ${bundle.checkpointPulse}..${bundle.pulse}, ${bundle.inputs.size} inputs)"
    )
    val outcome = replay(bundle)
    outcome.notes.forEach { echo("  note: $it") }
    if (outcome.reproduced) {
      val d = outcome.matching.first()
      echo("Reproduced at pulse ${d.pulse}: ${d.message}")
      d.stack?.let { echo(it) }
      return
    }
    echo("Not reproduced. The replay logged ${outcome.diagnostics.size} other diagnostics:")
    outcome.diagnostics.forEach { echo("  [${it.kind}] ${it.message}") }
    throw ProgramResult(1)
  }

  /** The bundle text: the stored one of a diagnostic id, or the contents of a file. */
  private fun bundleJson(): String {
    val id = source.toLongOrNull()
    if (id != null) return diagnosticBundleJson(id)
    return File(source).takeIf { it.isFile }?.readText()
      ?: throw UsageError("'$source' is neither a diagnostic id nor a bundle file")
  }

  private fun diagnosticBundleJson(id: Long): String {
    val path = dbPath(world, db)
    if (!path.toFile().isFile) throw UsageError("no database at $path")
    return Database.open(path).use { d ->
      val row = d.diagnostic(id) ?: throw UsageError("no diagnostic $id in $path")
      row.reproJson
        ?: throw UsageError(
          "diagnostic $id has no repro bundle (only script errors in a room have one)"
        )
    }
  }

  private fun replay(bundle: ReproBundle): ReproRunner.Outcome {
    val dir = WorldDir((world ?: File(".")).toPath())
    return withPlugins(dir) { plugins ->
      ReproRunner(dir, plugins)
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
        val progress = (o?.get("progress") as? JsonPrimitive)?.content
        when {
          progress != null -> echo(progress)
          o?.containsKey("ok") == true -> ok = echoResult(o)
          else -> echo(line, err = true)
        }
      }
    if (status != 200 || !ok) throw ProgramResult(1)
  }

  /** Prints the final `{"ok"}` line of a deploy and says whether it succeeded. */
  private fun echoResult(o: JsonObject): Boolean {
    val ok = (o["ok"] as? JsonPrimitive)?.content == "true"
    if (ok) {
      echo("Deployed ${(o["commit"] as? JsonPrimitive)?.content}.")
    } else {
      val errors = (o["errors"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
      errors.forEach { echo("error: $it", err = true) }
    }
    return ok
  }
}
