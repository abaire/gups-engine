package gups.cli

import gups.core.model.ItemLocation
import gups.core.world.spawnItem
import gups.script.WorldDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MigrationCheckerTest {

  @Test
  fun `version bump with no migrate and not in migrate_noop fails`() {
    // 1. Create a snapshot with mob at version 1
    val db = tempDb()
    val pw = PersistWorld(db, P.protos(ratVersion = 1))
    pw.save()
    pw.close()

    // 2. PR's world has mob at version 2 with no migrate() and no migrate_noop
    val worldRoot =
      tempWorld(
        mapOf(
          "t/package.star" to "package(api_version = 1, kind = \"zone\")\n",
          "t/content.star" to
            """
            room("hall", name = "Hall", flags = ["dead_end_ok"], exits = [])
            room("vault", name = "Vault", flags = ["dead_end_ok"], exits = [])
            mob("rat", name = "Rat", version = 2)
            item("coin", name = "Coin")
            item("bag", name = "Bag")
            item("gem", name = "Gem")
            """
              .trimIndent(),
        )
      )

    val outcome = MigrationChecker(WorldDir(worldRoot), db).check()
    assertFalse(outcome.ok)
    assertTrue(
      outcome.problems.any { "version bump for //t:rat (v1 -> v2) has no migrate()" in it }
    )
  }

  @Test
  fun `version bump in migrate_noop succeeds without migrate`() {
    val db = tempDb()
    val pw = PersistWorld(db, P.protos(ratVersion = 1))
    pw.save()
    pw.close()

    val worldRoot =
      tempWorld(
        mapOf(
          "t/package.star" to
            "package(api_version = 1, kind = \"zone\", migrate_noop = [\":rat\"])\n",
          "t/content.star" to
            """
            room("hall", name = "Hall", flags = ["dead_end_ok"], exits = [])
            room("vault", name = "Vault", flags = ["dead_end_ok"], exits = [])
            mob("rat", name = "Rat", version = 2)
            item("coin", name = "Coin")
            item("bag", name = "Bag")
            item("gem", name = "Gem")
            """
              .trimIndent(),
        )
      )

    val outcome = MigrationChecker(WorldDir(worldRoot), db).check()
    assertTrue(outcome.ok, outcome.problems.joinToString())
    assertEquals(2, outcome.versionUpgrades)
  }

  @Test
  fun `version bump with migrate hook succeeds`() {
    val db = tempDb()
    val pw = PersistWorld(db, P.protos(ratVersion = 1))
    pw.save()
    pw.close()

    val worldRoot =
      tempWorld(
        mapOf(
          "t/package.star" to "package(api_version = 1, kind = \"zone\")\n",
          "t/content.star" to
            """
            def migrate(old_version, attrs):
                return attrs
            room("hall", name = "Hall", flags = ["dead_end_ok"], exits = [])
            room("vault", name = "Vault", flags = ["dead_end_ok"], exits = [])
            mob("rat", name = "Rat", version = 2)
            item("coin", name = "Coin")
            item("bag", name = "Bag")
            item("gem", name = "Gem")
            """
              .trimIndent(),
        )
      )

    val outcome = MigrationChecker(WorldDir(worldRoot), db).check()
    assertTrue(outcome.ok, outcome.problems.joinToString())
    assertEquals(2, outcome.versionUpgrades)
  }

  @Test
  fun `unexpected tombstone in player custody fails`() {
    val db = tempDb()
    val pw = PersistWorld(db, P.protos())
    val player = pw.login("Ann")
    pw.w.spawnItem(P.GEM, ItemLocation.Carried(player.id))
    pw.save()
    pw.close()

    // PR's world removes the gem without declaring expected_tombstones
    val worldRoot =
      tempWorld(
        mapOf(
          "t/package.star" to "package(api_version = 1, kind = \"zone\")\n",
          "t/content.star" to
            """
            room("hall", name = "Hall", flags = ["dead_end_ok"], exits = [])
            room("vault", name = "Vault", flags = ["dead_end_ok"], exits = [])
            mob("rat", name = "Rat", version = 1)
            item("coin", name = "Coin")
            item("bag", name = "Bag")
            """
              .trimIndent(),
        )
      )

    val outcome = MigrationChecker(WorldDir(worldRoot), db).check()
    assertFalse(outcome.ok)
    assertTrue(
      outcome.problems.any {
        "tombstone for //t:gem is not listed in package(expected_tombstones = [...])" in it
      }
    )
  }

  @Test
  fun `declared tombstone in package expected_tombstones succeeds`() {
    val db = tempDb()
    val pw = PersistWorld(db, P.protos())
    val player = pw.login("Ann")
    pw.w.spawnItem(P.GEM, ItemLocation.Carried(player.id))
    pw.save()
    pw.close()

    val worldRoot =
      tempWorld(
        mapOf(
          "t/package.star" to
            "package(api_version = 1, kind = \"zone\", expected_tombstones = [\":gem\"])\n",
          "t/content.star" to
            """
            room("hall", name = "Hall", flags = ["dead_end_ok"], exits = [])
            room("vault", name = "Vault", flags = ["dead_end_ok"], exits = [])
            mob("rat", name = "Rat", version = 1)
            item("coin", name = "Coin")
            item("bag", name = "Bag")
            """
              .trimIndent(),
        )
      )

    val outcome = MigrationChecker(WorldDir(worldRoot), db).check()
    assertTrue(outcome.ok, outcome.problems.joinToString())
    assertEquals(1, outcome.expectedTombstones)
  }

  @Test
  fun `snapshot age is read from meta snapshot_taken_at and formatted`() {
    val db = tempDb()
    val pw = PersistWorld(db, P.protos())
    pw.save()
    pw.close()

    val twoDaysAgo = java.time.Instant.now().epochSecond - 2 * 86400
    java.sql.DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { c ->
      c.createStatement().use { st ->
        st.executeUpdate(
          "INSERT OR REPLACE INTO meta (key, value) VALUES ('snapshot_taken_at', '$twoDaysAgo')"
        )
      }
    }

    val worldRoot =
      tempWorld(
        mapOf(
          "t/package.star" to "package(api_version = 1, kind = \"zone\")\n",
          "t/content.star" to
            """
            room("hall", name = "Hall", flags = ["dead_end_ok"], exits = [])
            room("vault", name = "Vault", flags = ["dead_end_ok"], exits = [])
            mob("rat", name = "Rat", version = 1)
            item("coin", name = "Coin")
            item("bag", name = "Bag")
            item("gem", name = "Gem")
            """
              .trimIndent(),
        )
      )

    val outcome = MigrationChecker(WorldDir(worldRoot), db).check()
    assertTrue(outcome.ok, outcome.problems.joinToString())
    assertNotNull(outcome.snapshotAge)
    assertTrue(outcome.snapshotAge.toDays() >= 1)
    assertNotNull(outcome.snapshotAgeText)
    assertTrue(outcome.snapshotAgeText.contains("2 days"))
  }
}
