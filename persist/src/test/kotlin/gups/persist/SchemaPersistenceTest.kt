package gups.persist

import gups.core.Account
import gups.core.Role
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Condition
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.SchemaName
import gups.core.world.changeVital
import gups.core.world.spawnItem
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SchemaPersistenceTest {
  private val robot = FixtureSchemas.ROBOT

  @Test
  fun `a player and a mob round trip under the robot schema`() {
    FixtureSchemas.current = robot
    try {
      val file = tempDb()
      val a = PersistWorld(file, fs = robot)
      val ann = a.login("Ann")
      val rat = a.w.actors.filterIsInstance<Mob>().first()
      a.run(7)
      a.w.changeVital(ann, "rads", -150)
      a.w.changeVital(ann, "power", -30)
      a.w.changeVital(rat, "integrity", -4)
      a.w.changeVital(rat, "rads", -rat.current("rads") - 2)
      a.save()
      a.close()

      val b = PersistWorld(file, fs = robot)
      val ann2 = b.login("Ann")
      assertEquals(robot.vitals, ann2.vitals.keys.toList(), "vitals come back in schema order")
      for (v in robot.vitals) {
        assertEquals(ann.current(v), ann2.current(v), v)
        assertEquals(ann.max(v), ann2.max(v), v)
        assertEquals(
          ann.vitals.getValue(v).belowZeroSince,
          ann2.vitals.getValue(v).belowZeroSince,
          v,
        )
      }
      assertEquals(-150, ann2.current("rads"))
      assertEquals(7L, ann2.vitals.getValue("rads").belowZeroSince)
      assertEquals(ann.baseStats, ann2.baseStats)
      assertEquals(Condition.OK, ann2.condition)
      val rat2 = b.w.actor(rat.id) as Mob
      for (v in robot.vitals) assertEquals(rat.current(v), rat2.current(v), v)
      assertEquals(
        rat.vitals.getValue("rads").belowZeroSince,
        rat2.vitals.getValue("rads").belowZeroSince,
      )
      b.close()
    } finally {
      FixtureSchemas.current = FixtureSchemas.FANTASY
    }
  }

  @Test
  fun `payloads are keyed by schema name`() {
    val file = tempDb()
    val a = PersistWorld(file)
    a.login("Ann")
    a.save()
    val payload = a.persistence.db.playerRows().single().payload
    assertContains(payload, "\"vitals\":{\"hp\":{\"cur\":20,\"max\":20}")
    assertContains(payload, "\"stats\":{\"str\":10")
    assertContains(payload, "\"format_version\":3")
    a.close()
  }

  @Test
  fun `the first start records the schema and a matching start passes`() {
    val p = SqlitePersistence.open(tempDb())
    val names = FixtureSchemas.FANTASY.schema.names
    assertNull(p.worldSchema)
    assertNull(p.checkSchema(names))
    assertNotNull(p.worldSchema)
    assertNull(p.checkSchema(names))
    p.close()
  }

  @Test
  fun `a changed schema refuses to start, listing what changed and pointing to wipe`() {
    val file = tempDb()
    SqlitePersistence.open(file).also {
      it.checkSchema(FixtureSchemas.FANTASY.schema.names)
      it.close()
    }
    val changed =
      FixtureSchemas.FANTASY.schema.copy(
        stats =
          FixtureSchemas.FANTASY.schema.stats.filter { it.name != "wis" } +
            SchemaName("luck", "Luck"),
        damageTypes = FixtureSchemas.FANTASY.schema.damageTypes + "acid",
      )
    val p = SqlitePersistence.open(file)
    val message = assertNotNull(p.checkSchema(changed.names))
    assertContains(message, "stats: added luck; removed wis")
    assertContains(message, "damage types: added acid")
    assertFalse("vitals" in message)
    assertContains(message, "--wipe")
    p.close()
  }

  @Test
  fun `a change of labels, prompt and unarmed damage type alone starts`() {
    val file = tempDb()
    val base = FixtureSchemas.FANTASY.schema
    SqlitePersistence.open(file).also {
      it.checkSchema(base.names)
      it.close()
    }
    val relabelled =
      base.copy(
        stats = base.stats.map { it.copy(label = it.label.uppercase()) },
        vitals = base.vitals.map { it.copy(label = "Renamed ${it.name}") },
        unarmedDamageType = "slash",
        prompt = "> ",
      )
    val p = SqlitePersistence.open(file)
    assertNull(p.checkSchema(relabelled.names))
    p.close()
  }

  @Test
  fun `wipe removes characters and items, keeps accounts, takes a prewipe backup and records the schema`() {
    val file = tempDb()
    val a = PersistWorld(file)
    val ann = a.login("Ann")
    a.w.spawnItem(P.GEM, ItemLocation.Carried(ann.id))
    a.w.spawnItem(P.GEM, ItemLocation.InRoom(a.w.room(P.HALL).id, 0))
    a.persistence.accounts.create(Account(0, "ann", "hash", Role.PLAYER))
    a.save()
    val fantasy = FixtureSchemas.FANTASY.schema.names
    a.persistence.checkSchema(fantasy)
    val robotNames = robot.schema.names
    val summary = a.persistence.wipe(robotNames)
    assertEquals(1, summary.characters)
    assertEquals(2, summary.items)
    assertTrue(Files.exists(summary.backup))
    assertTrue(summary.backup.fileName.toString().startsWith("prewipe-"))
    assertEquals(emptyList(), a.persistence.db.playerRows())
    assertTrue(a.persistence.db.entityRows().none { it.kind == "item" })
    assertEquals(listOf("ann"), a.persistence.accounts.all().map { it.login })
    assertEquals(SchemaRecord.encode(robotNames), a.persistence.db.meta()["world_schema"])
    a.close()
    Database.open(summary.backup).use { backup ->
      assertEquals(listOf("Ann"), backup.playerRows().map { it.name })
    }
  }

  @Test
  fun `sanitized snapshots keep the schema record`() {
    val file = tempDb()
    val p = SqlitePersistence.open(file)
    p.checkSchema(FixtureSchemas.FANTASY.schema.names)
    val copy = Files.createTempDirectory("gups-snap").resolve("snapshot.db")
    p.db.sanitizedCopy(copy)
    p.close()
    Database.open(copy).use {
      assertEquals(
        SchemaRecord.encode(FixtureSchemas.FANTASY.schema.names),
        it.meta()["world_schema"],
      )
    }
  }
}
