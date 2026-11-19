package gups.pluginapi

import gups.core.Label
import gups.core.model.Mob
import gups.core.world.World
import gups.core.world.despawn
import gups.core.world.destroy
import gups.pluginapi.host.ActorFacade
import gups.pluginapi.host.ItemFacade
import gups.pluginapi.host.WorldFacade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FacadeSpawnersTest {
  private val w: World = world(files = SPAWNER_FILES)
  private val f = WorldFacade(w, "test") {}

  @Test
  fun `spawnedBy returns PluginSpawner for placed item and mob and null for others`() {
    val room = f.room("//zones/z:a")!!
    val key = room.items.single()
    val rat = room.actors.single()

    val keySp = key.spawnedBy
    assertNotNull(keySp)
    assertEquals("//zones/z:vault_key", keySp.id)
    assertEquals("//zones/z:key", keySp.label)
    assertEquals("//zones/z:a", keySp.room)
    assertEquals(listOf("//zones/z:a"), keySp.rooms)

    val ratSp = rat.spawnedBy
    assertNotNull(ratSp)
    assertNull(ratSp.id)
    assertEquals("//zones/z:rat", ratSp.label)
    assertEquals("//zones/z:a", ratSp.room)
    assertEquals(listOf("//zones/z:a"), ratSp.rooms)
    assertEquals(0, ratSp.n)

    // Manually spawned mob/item and players have spawnedBy == null.
    val manualItem = f.spawnItem("//zones/z:key", room).single()
    assertNull(manualItem.spawnedBy)
    val manualMob = f.spawnMob("//zones/z:rat", room).single()
    assertNull(manualMob.spawnedBy)
    val player = ActorFacade(w.spawnPlayer("Ann", Label("//zones/z:a")), w)
    assertNull(player.spawnedBy)
  }

  @Test
  fun `spawnerInfo and refill work by full label and by PluginSpawner`() {
    val roomA = w.room(Label("//zones/z:a"))
    val key = roomA.items.single()
    val rat = roomA.actors.filterIsInstance<Mob>().single()
    val keyHandle = ItemFacade(key, w).spawnedBy!!
    val ratHandle = ActorFacade(rat, w).spawnedBy!!

    // By full label
    val infoByLabel = f.spawnerInfo("//zones/z:vault_key")
    assertEquals(
      PluginSpawnerInfo(live = 1, held = 0, max = 1, nextAt = null, ran = true),
      infoByLabel,
    )

    // By PluginSpawner (named)
    assertEquals(infoByLabel, f.spawnerInfo(keyHandle))

    // Destroy key and refill by label
    w.destroy(key)
    assertEquals(0, f.spawnerInfo("//zones/z:vault_key").live)
    f.refill("//zones/z:vault_key")
    assertEquals(1, f.spawnerInfo("//zones/z:vault_key").live)
    assertEquals(1, roomA.items.size)

    // Unnamed room rule by PluginSpawner
    assertEquals(1, f.spawnerInfo(ratHandle).live)
    w.despawn(rat)
    assertEquals(0, f.spawnerInfo(ratHandle).live)
    f.refill(ratHandle, count = 1)
    assertEquals(1, f.spawnerInfo(ratHandle).live)
    assertTrue(roomA.actors.filterIsInstance<Mob>().size == 1)

    // Unknown rule throws IllegalArgumentException
    assertFailsWith<IllegalArgumentException> { f.spawnerInfo("//zones/z:missing") }
    assertFailsWith<IllegalArgumentException> { f.refill("//zones/z:missing") }
  }
}

private val SPAWNER_FILES =
  mapOf(
    "zones/z/package.star" to "package(api_version = 1, kind = \"zone\")",
    "zones/z/rooms.star" to
      """
      item("key", name = "a key")
      mob("rat", name = "a rat", vitals = vitals(hp = 10))
      room("a", name = "A", spawns = [
          spawn(":key", id = "vault_key", max = 1, respawn = "never"),
          spawn(":rat", max = 1, respawn = "never"),
      ])
      """,
  )
