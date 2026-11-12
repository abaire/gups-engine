package gups.persist

import gups.core.fixtures.tempDb
import gups.core.model.LevelGain
import gups.core.model.Mob
import gups.core.world.quit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LevelGainsPersistenceTest {
  @Test
  fun `levelGains round-trips for a player`() {
    val file = tempDb()
    val a = PersistWorld(file)
    val ann = a.login("Ann")
    ann.levelGains[2] = LevelGain(stats = mapOf("str" to 2), max = mapOf("hp" to 5))
    ann.levelGains[3] = LevelGain(max = mapOf("mana" to 3))
    ann.dirty = true
    a.w.quit(ann)
    a.save()
    a.close()

    val db = Database.open(file)
    val playerRow = db.playerRows().single { it.name == "Ann" }
    val gains = assertNotNull(levelGainsJson(playerRow.payload))
    assertEquals(setOf("2", "3"), gains.keys)
    val two = gains.getValue("2").jsonObject
    assertEquals(setOf("stats", "max"), two.keys)
    assertEquals(mapOf("str" to 2), two.intMap("stats"))
    assertEquals(mapOf("hp" to 5), two.intMap("max"))
    val three = gains.getValue("3").jsonObject
    assertEquals(setOf("max"), three.keys)
    assertEquals(mapOf("mana" to 3), three.intMap("max"))
    db.close()

    val b = PersistWorld(file)
    val ann2 = b.login("Ann")
    assertEquals(
      mapOf(
        2 to LevelGain(stats = mapOf("str" to 2), max = mapOf("hp" to 5)),
        3 to LevelGain(max = mapOf("mana" to 3)),
      ),
      ann2.levelGains,
    )
    b.close()
  }

  @Test
  fun `levelGains round-trips for a mob, and an empty map writes nothing to the mob delta`() {
    val file = tempDb()
    val a = PersistWorld(file)
    val (rat, other) = a.w.actors.filterIsInstance<Mob>()
    rat.levelGains[2] = LevelGain(stats = mapOf("dex" to 1), max = mapOf("hp" to 2))
    rat.dirty = true
    other.dirty = true
    a.save()
    a.close()

    val db = Database.open(file)
    val rows = db.entityRows()
    val ratRow = rows.single { it.id == rat.id.v }
    val otherRow = rows.single { it.id == other.id.v }
    val ratGains = assertNotNull(levelGainsJson(ratRow.payload))
    assertEquals(setOf("2"), ratGains.keys)
    val ratTwo = ratGains.getValue("2").jsonObject
    assertEquals(mapOf("dex" to 1), ratTwo.intMap("stats"))
    assertEquals(mapOf("hp" to 2), ratTwo.intMap("max"))
    assertFalse("levelGains" in Json.parseToJsonElement(otherRow.payload).jsonObject.keys)
    db.close()

    val b = PersistWorld(file)
    val rat2 = b.w.actor(rat.id) as Mob
    val other2 = b.w.actor(other.id) as Mob
    assertEquals(
      mapOf(2 to LevelGain(stats = mapOf("dex" to 1), max = mapOf("hp" to 2))),
      rat2.levelGains,
    )
    assertTrue(other2.levelGains.isEmpty())
    b.close()
  }

  private fun levelGainsJson(payload: String): JsonObject? =
    Json.parseToJsonElement(payload).jsonObject["levelGains"]?.jsonObject

  private fun JsonObject.intMap(key: String): Map<String, Int>? =
    this[key]?.jsonObject?.mapValues { it.value.jsonPrimitive.int }
}
