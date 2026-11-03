package gups.cli

import gups.core.Account
import gups.core.Role
import gups.core.world.spawnItem
import gups.persist.Database
import gups.persist.SqlitePersistence
import gups.script.WorldDir
import gups.script.testing.TestWorlds
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class SchemaStartupTest {
  @TempDir lateinit var tmp: Path

  private fun copyTemplate(): Path =
    TestWorlds.copyWorld(to = Files.createTempDirectory(tmp, "world"))

  /** Boots a server on [root] and [db]; returns whether it started and what it logged. */
  private fun boot(root: Path, db: Path, wipe: Boolean = false): Pair<Boolean, String> {
    val logs = StringBuffer()
    val server =
      GupsServer(WorldDir(root), port = 0, db = db, wipe = wipe) { logs.append(it).append('\n') }
    return server.boot() to logs.toString()
  }

  private fun recorded(db: Path): String? = Database.open(db).use { it.meta()["world_schema"] }

  @Test
  fun `the first start records the schema, and a restart with the same names runs`() {
    val root = copyTemplate()
    val db = tempDb()
    assertTrue(boot(root, db).first)
    assertTrue(recorded(db)!!.contains("\"hp\""))
    assertTrue(boot(root, db).first)
  }

  @Test
  fun `a changed schema refuses to start and says what changed`() {
    val root = copyTemplate()
    val db = tempDb()
    assertTrue(boot(root, db).first)
    val module = root.resolve("MODULE.gups")
    module.writeText(module.readText().replace("\"mana\",", "\"mana\", \"sanity\","))
    val (ok, log) = boot(root, db)
    assertFalse(ok)
    assertContains(log, "vitals: added sanity")
    assertContains(log, "--wipe")
  }

  @Test
  fun `a change of labels and prompt alone starts`() {
    val root = copyTemplate()
    val db = tempDb()
    assertTrue(boot(root, db).first)
    val module = root.resolve("MODULE.gups")
    module.writeText(
      module.readText().replace("Hit points", "Health").replace("<{hp}hp", "<{hp}/{max_hp}hp")
    )
    val (ok, log) = boot(root, db)
    assertTrue(ok, log)
  }

  @Test
  fun `wipe deletes characters and items, keeps accounts, takes a backup and records the schema`() {
    val root = copyTemplate()
    val db = tempDb()
    assertTrue(boot(root, db).first)
    SqlitePersistence.open(db).also {
      it.accounts.create(Account(0, "ann", "hash", Role.PLAYER))
      it.close()
    }
    // A character with an item, as a previous term would have left.
    val seeded = PersistWorld(db, P.protos())
    val ann = seeded.login("Ann")
    seeded.w.spawnItem(P.GEM, gups.core.model.ItemLocation.Carried(ann.id))
    seeded.save()
    seeded.close()

    val module = root.resolve("MODULE.gups")
    module.writeText(module.readText().replace("\"mana\",", "\"mana\", \"sanity\","))
    val (refused, _) = boot(root, db)
    assertFalse(refused)

    val (ok, log) = boot(root, db, wipe = true)
    assertTrue(ok, log)
    assertContains(log, "Wiped 1 characters and 1 items")
    assertContains(log, "prewipe-")
    Database.open(db).use {
      assertEquals(emptyList(), it.playerRows())
      assertTrue(
        it.entityRows().none { row -> row.protoLabel == P.GEM.v },
        "the carried gem is gone",
      )
      assertContains(it.meta().getValue("world_schema"), "sanity")
    }
    SqlitePersistence.open(db).also {
      assertContains(it.accounts.all().map { a -> a.login }, "ann")
      it.close()
    }
    assertTrue(
      Files.list(db.resolveSibling("backups")).use { s ->
        s.anyMatch { "prewipe-" in it.fileName.toString() }
      },
      "a prewipe backup exists",
    )
    val again = boot(root, db)
    assertTrue(again.first, "the new schema is the recorded one: ${again.second}")
  }

  @Test
  fun `a rules package that fails to load stops the server`() {
    val root = copyTemplate()
    val rules = root.resolve("rules/combat.star")
    rules.writeText(rules.readText().replace("def condition(ctx):", "def conditions(ctx):"))
    val (ok, log) = boot(root, tempDb())
    assertFalse(ok)
    assertContains(log, "rules package")
  }

  @Test
  fun `a world without MODULE gups doesn't start`() {
    val root = copyTemplate()
    Files.delete(root.resolve("MODULE.gups"))
    val (ok, log) = boot(root, tempDb())
    assertFalse(ok)
    assertContains(log, "MODULE.gups is required")
  }

  @Test
  fun `migrate-check fails when the snapshot's schema differs from the PR's`() {
    val root = copyTemplate()
    val snapshot = tempDb()
    assertTrue(boot(root, snapshot).first)
    val module = root.resolve("MODULE.gups")
    module.writeText(module.readText().replace("\"fire\"]", "\"fire\", \"acid\"]"))
    val outcome = MigrationChecker(WorldDir(root), snapshot).check()
    assertFalse(outcome.ok)
    assertContains(outcome.problems.single(), "this schema or feature change needs a server wipe")
    assertContains(outcome.problems.single(), "damage types: added acid")
  }

  @Test
  fun `migrate-check passes when the schema matches`() {
    val root = copyTemplate()
    val snapshot = tempDb()
    assertTrue(boot(root, snapshot).first)
    val outcome = MigrationChecker(WorldDir(root), snapshot).check()
    assertTrue(outcome.ok, outcome.problems.joinToString())
    assertNull(outcome.problems.firstOrNull())
  }
}
