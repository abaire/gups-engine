package gups.persist

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name

/**
 * Database snapshot management via SQLite's online-backup API.
 *
 * Captures non-blocking database copies into [dir] (defaulting to `backups/` adjacent to the
 * database file). Snapshots include WAL journal contents and are named `<kind>-<UTC
 * yyyyMMdd-HHmmss>.db`.
 *
 * ## Backup Kinds & Retention Policies
 * - **`hourly` ([Kind.HOURLY]):** Taken once per hour; retains the latest 48 snapshots (a 2-day
 *   rolling window).
 * - **`daily` ([Kind.DAILY]):** Taken once per day; retains the latest 14 snapshots (a 2-week
 *   rolling window).
 * - **`manual` ([Kind.MANUAL]):** On-demand snapshots requested via `gups backup` or the
 *   `@snapshot` command; never automatically pruned.
 * - **`predeploy` ([Kind.PREDEPLOY]):** Created automatically before applying package changes
 *   during hot deploy; never automatically pruned.
 * - **`prewipe` ([Kind.PREWIPE]):** Created automatically before resetting world state with
 *   `--wipe`; never automatically pruned.
 *
 * Automated backups are evaluated at server startup and checked every 5 minutes.
 */
class Backups(
  private val db: Database,
  val dir: Path = db.file.toAbsolutePath().parent.resolve("backups"),
) {
  /** The classification and retention schedule of a database snapshot. */
  enum class Kind(val word: String, val every: Duration?, val keep: Int) {
    /** Routine hourly snapshot; keeps 48. */
    HOURLY("hourly", Duration.ofHours(1), 48),
    /** Routine daily snapshot; keeps 14. */
    DAILY("daily", Duration.ofDays(1), 14),
    /** Explicitly requested via `gups backup` or `@snapshot`; never pruned. */
    MANUAL("manual", null, Int.MAX_VALUE),
    /** Created automatically prior to hot code deploys; never pruned. */
    PREDEPLOY("predeploy", null, Int.MAX_VALUE),
    /** Created automatically prior to resetting the world with `--wipe`; never pruned. */
    PREWIPE("prewipe", null, Int.MAX_VALUE),
  }

  private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

  /** Takes one snapshot now and prunes old ones of the same kind. */
  fun snapshot(kind: Kind, now: Instant = Instant.now()): Path {
    var target = dir.resolve("${kind.word}-${stamp.format(now)}.db")
    var n = 1
    while (Files.exists(target)) target = dir.resolve("${kind.word}-${stamp.format(now)}-${n++}.db")
    db.backupTo(target)
    prune(kind)
    return target
  }

  fun list(kind: Kind): List<Path> {
    if (!Files.isDirectory(dir)) return emptyList()
    Files.list(dir).use { s ->
      return s.filter { it.name.startsWith("${kind.word}-") && it.name.endsWith(".db") }
        .sorted()
        .toList()
    }
  }

  private fun prune(kind: Kind) {
    val all = list(kind)
    for (old in all.dropLast(kind.keep)) Files.deleteIfExists(old)
  }

  /** Takes the hourly and daily snapshots that are due; call every few minutes. */
  fun runDue(now: Instant = Instant.now()): List<Path> =
    listOf(Kind.HOURLY, Kind.DAILY).mapNotNull { kind ->
      val last = list(kind).lastOrNull()?.getLastModifiedTime()?.toInstant()
      if (last == null || Duration.between(last, now) >= kind.every) snapshot(kind, now) else null
    }
}
