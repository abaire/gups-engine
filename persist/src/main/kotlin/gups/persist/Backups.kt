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
 * Database snapshots through the online-backup API: hourly (keep 48), daily (keep 14), and on
 * demand. Files are named `<kind>-<UTC time>.db` in [dir].
 */
class Backups(
  private val db: Database,
  val dir: Path = db.file.toAbsolutePath().parent.resolve("backups"),
) {
  enum class Kind(val word: String, val every: Duration?, val keep: Int) {
    HOURLY("hourly", Duration.ofHours(1), 48),
    DAILY("daily", Duration.ofDays(1), 14),
    MANUAL("manual", null, Int.MAX_VALUE),
    PREDEPLOY("predeploy", null, Int.MAX_VALUE),
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
