package gups.cli

import gups.core.Label
import gups.core.world.Features
import gups.core.world.World
import gups.core.world.WorldConfig
import gups.persist.EntityRow
import gups.persist.FeatureRecord
import gups.persist.ItemPayload
import gups.persist.LocationJson
import gups.persist.SchemaRecord
import gups.persist.SqlitePersistence
import gups.script.ScriptLimits
import gups.script.ScriptLoader
import gups.script.StarlarkHost
import gups.script.WorldDir
import gups.script.WorldPluginWiring
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * Checks a PR's world against a production migration snapshot (gate 5).
 *
 * Verifies that:
 * 1. Every prototype version bump has a `migrate()` hook or is declared in `package(migrate_noop =
 *    [...])`.
 * 2. Any item in player custody whose prototype is gone is declared in `package(expected_tombstones
 *    = [...])`.
 * 3. Restoration and migration execute cleanly without script errors.
 */
class MigrationChecker(
  private val worldDir: WorldDir,
  private val snapshotDb: Path,
  private val plugins: WorldPluginWiring = WorldPluginWiring.NONE,
) {
  data class Outcome(
    val ok: Boolean,
    val problems: List<String>,
    val entitiesChecked: Int = 0,
    val charactersChecked: Int = 0,
    val versionUpgrades: Int = 0,
    val expectedTombstones: Int = 0,
    val snapshotAge: java.time.Duration? = null,
    val snapshotAgeText: String? = null,
  )

  private val json = Json { ignoreUnknownKeys = true }

  fun check(): Outcome {
    val loader = ScriptLoader(worldDir, ScriptLimits(), pluginGlobals = plugins.globals)
    val loadResult = loader.load(worldDir.packages())
    if (!loadResult.ok) {
      return Outcome(ok = false, problems = loadResult.errors.map { it.render() })
    }

    val persistence = SqlitePersistence.open(snapshotDb)
    try {
      // A database from before feature modules ran with all of them.
      (persistence.worldFeatures
          ?: persistence.worldSchema?.let { FeatureRecord.encode(Features.ALL) })
        ?.let { saved ->
          FeatureRecord.mismatch(saved, loader.module.features)?.let {
            return Outcome(
              ok = false,
              problems = listOf("this schema or feature change needs a server wipe:\n$it"),
            )
          }
        }
      persistence.worldSchema?.let { saved ->
        SchemaRecord.mismatch(saved, loader.schema.names)?.let {
          return Outcome(
            ok = false,
            problems = listOf("this schema or feature change needs a server wipe:\n$it"),
          )
        }
      }
      val db = persistence.db
      val entityRows = db.entityRows()
      val playerRows = db.playerRows()

      val snapshotTime =
        db.snapshotTakenAt()
          ?: runCatching { java.nio.file.Files.getLastModifiedTime(snapshotDb).toInstant() }
            .getOrNull()
      val age = snapshotTime?.let { java.time.Duration.between(it, java.time.Instant.now()) }
      val ageText = age?.let(::formatAge)

      val host = StarlarkHost(loadResult, ScriptLimits(), loader)
      val boot = persistence.boot(loadResult.prototypes(), fresh = false)
      val config =
        WorldConfig(
          aliases = loader.module.aliases,
          features = loader.module.features,
          rename = loader.module.rename,
        )
      val world =
        World(loadResult.prototypes(), loader.schema, host.rules(), host, config, boot = boot)
      plugins.install(world)
      host.install(world)
      plugins.started(world)

      val playerIds = playerRows.map { it.id }.toSet()
      val entityMap = entityRows.associateBy { it.id }

      fun ownerOf(row: EntityRow, seen: Int = 0): Long? {
        val loc =
          row.location?.let { json.decodeFromString(LocationJson.serializer(), it) } ?: return null
        val holder = loc.carried ?: loc.equipped
        if (holder != null) return holder.takeIf { it in playerIds }
        val c = loc.contained ?: return null
        if (seen > 64) return null
        return entityMap[c]?.let { ownerOf(it, seen + 1) }
      }

      val problems = mutableListOf<String>()
      var versionUpgrades = 0
      var expectedTombstoneCount = 0

      // 1. Check version bumps
      for (row in entityRows) {
        val label = Label(row.protoLabel)
        val protoVersion =
          when (row.kind) {
            "mob" -> world.protos.mobs[label]?.version
            "item" -> world.protos.items[label]?.version
            "room" -> world.protos.rooms[label]?.version
            else -> null
          }
        if (protoVersion != null && protoVersion > row.protoVersion) {
          versionUpgrades++
          val pkg = loadResult.packages[label.pkg]
          val hasMigrate = pkg?.migrate != null
          val isNoop = pkg?.migrateNoop?.contains(label) == true
          if (!hasMigrate && !isNoop) {
            problems +=
              "version bump for $label (v${row.protoVersion} -> v$protoVersion) has no migrate() and is not listed in migrate_noop"
          }
        }
      }

      // 2. Check tombstones produced in player custody
      for (row in entityRows) {
        if (row.kind != "item") continue
        if (ownerOf(row) == null) continue
        val payload = json.decodeFromString(ItemPayload.serializer(), row.payload)
        if (payload.tombstone != null) continue // already a tombstone before this check

        val label = Label(row.protoLabel)
        if (label !in world.protos.items) {
          val pkg = loadResult.packages[label.pkg]
          if (pkg != null && label in pkg.expectedTombstones) {
            expectedTombstoneCount++
          } else {
            problems += "tombstone for $label is not listed in package(expected_tombstones = [...])"
          }
        }
      }

      // 3. Run full restoration to verify migrate() callbacks execute cleanly
      persistence.restore(world, fresh = false)
      for (player in playerRows) {
        persistence.loadCharacter(world, player.name)
      }

      for (d in world.diagnostics) {
        if (d.kind in setOf("script_error", "step_limit", "wall_clock", "alloc_limit")) {
          problems += "script error during migration restore: [${d.kind}] ${d.message}"
        }
      }

      return Outcome(
        ok = problems.isEmpty(),
        problems = problems,
        entitiesChecked = entityRows.size,
        charactersChecked = playerRows.size,
        versionUpgrades = versionUpgrades,
        expectedTombstones = expectedTombstoneCount,
        snapshotAge = age,
        snapshotAgeText = ageText,
      )
    } finally {
      persistence.close()
    }
  }

  companion object {
    fun formatAge(d: java.time.Duration): String {
      val totalSeconds = d.seconds.coerceAtLeast(0)
      val days = totalSeconds / 86400
      val hours = (totalSeconds % 86400) / 3600
      val minutes = (totalSeconds % 3600) / 60
      return when {
        days > 0 -> if (hours > 0) "$days days $hours hours old" else "$days days old"
        hours > 0 -> if (minutes > 0) "$hours hours $minutes minutes old" else "$hours hours old"
        minutes > 0 -> "$minutes minutes old"
        else -> "less than a minute old"
      }
    }
  }
}
