package gups.persist

import gups.core.EntityId
import gups.core.fixtures.tempDb
import gups.core.model.ItemLocation
import gups.core.model.LeashOverride
import gups.core.model.Mob
import gups.core.world.petsOf
import gups.core.world.quit
import gups.core.world.setLeash
import gups.core.world.setOwner
import gups.core.world.spawnItem
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PetPersistenceTest {
  @Test
  fun `a pet's owner survives a restart, and its owner reason is not restored`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val ann = first.login("Ann")
    val (rat, other) = first.w.actors.filterIsInstance<Mob>()
    first.w.setOwner(rat, ann)
    assertTrue("owner:${ann.id.v}" in rat.activeReasons)
    first.save()
    first.close()

    val second = PersistWorld(file)
    assertNull(second.w.actor(rat.id), "held out of the world until owner logs in")
    val ann2 = second.login("Ann")
    val back = second.w.actor(rat.id) as Mob
    assertEquals(ann2.id, back.owner)
    assertTrue("owner:${ann2.id.v}" in back.activeReasons)
    assertNull((second.w.actor(other.id) as Mob).owner)
    second.close()
  }

  @Test
  fun `a summon's summoner survives a restart`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val (rat, other) = first.w.actors.filterIsInstance<Mob>()
    rat.summonedBy = EntityId(42)
    first.save()
    first.close()

    val second = PersistWorld(file)
    assertEquals(EntityId(42), (second.w.actor(rat.id) as Mob).summonedBy)
    assertNull((second.w.actor(other.id) as Mob).summonedBy)
    second.close()
  }

  @Test
  fun `leash overrides survive a restart`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val (r1, r2) = first.w.actors.filterIsInstance<Mob>()
    val r3 = first.w.spawnMob(P.RAT, first.w.room(P.HALL), 2)
    first.w.setLeash(r2, 3)
    first.w.setLeash(r3, null)
    first.save()
    first.close()

    val second = PersistWorld(file)
    assertNull((second.w.actor(r1.id) as? Mob)?.leashOverride, "no override is not saved")
    assertEquals(LeashOverride(3), (second.w.actor(r2.id) as Mob).leashOverride)
    assertEquals(
      LeashOverride(null),
      (second.w.actor(r3.id) as Mob).leashOverride,
      "an unleashed override is saved",
    )
    second.close()
  }

  @Test
  fun `quit with a pet carrying an item keeps rows and restores pet beside player with item on login`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val ann = first.login("Ann")
    val rat = first.w.actors.filterIsInstance<Mob>().first()
    first.w.setOwner(rat, ann)
    val gem = first.w.spawnItem(P.GEM, ItemLocation.Carried(rat.id))
    first.w.quit(ann)
    first.save()
    first.close()

    assertEquals(1, count(file, "SELECT count(*) FROM world_entities WHERE id = ${rat.id.v}"))
    assertEquals(1, count(file, "SELECT count(*) FROM world_entities WHERE id = ${gem.id.v}"))

    val second = PersistWorld(file)
    assertNull(second.w.actor(rat.id), "held out of the world at boot")
    assertNull(second.w.item(gem.id), "pet item held out of the world at boot")

    val ann2 = second.login("Ann")
    val back = second.w.actor(rat.id) as Mob
    assertNotNull(back)
    assertEquals(rat.id, back.id)
    assertEquals(ann2.room, back.room)
    assertEquals(ann2.id, back.owner)
    assertTrue("owner:${ann2.id.v}" in back.activeReasons)
    assertNotNull(back.ownedBehaviors)

    val backGem = second.w.item(gem.id)
    assertNotNull(backGem)
    assertEquals(ItemLocation.Carried(back.id), backGem.location)
    assertTrue(backGem in back.inventory)
    second.close()
  }

  @Test
  fun `shutdown checkpoint with owner online holds pet out of the world until owner logs in`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val ann = first.login("Ann")
    val rat = first.w.actors.filterIsInstance<Mob>().first()
    first.w.setOwner(rat, ann)
    first.save()
    first.close()

    val second = PersistWorld(file)
    assertNull(second.w.actor(rat.id), "pet is not in the world until owner logs in")

    val ann2 = second.login("Ann")
    val back = second.w.actor(rat.id) as Mob
    assertNotNull(back)
    assertEquals(ann2.id, back.owner)
    second.close()
  }

  @Test
  fun `owner character deleted deletes held pet and its items with a note`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val ann = first.login("Ann")
    val rat = first.w.actors.filterIsInstance<Mob>().first()
    first.w.setOwner(rat, ann)
    val gem = first.w.spawnItem(P.GEM, ItemLocation.Carried(rat.id))
    first.w.quit(ann)
    first.save()
    first.close()

    DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
      c.createStatement().use { st -> st.executeUpdate("DELETE FROM players WHERE name = 'Ann'") }
    }

    val second = PersistWorld(file)
    assertContains(
      second.report.notes,
      "pet ${rat.proto.label} #${rat.id.v} dropped: its owner is gone",
    )
    assertNull(second.w.actor(rat.id))
    assertNull(second.w.item(gem.id))
    second.save()
    second.close()

    assertEquals(0, count(file, "SELECT count(*) FROM world_entities WHERE id = ${rat.id.v}"))
    assertEquals(0, count(file, "SELECT count(*) FROM world_entities WHERE id = ${gem.id.v}"))
  }

  @Test
  fun `a fresh start also deletes a held pet whose owner is gone`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val ann = first.login("Ann")
    val rat = first.w.actors.filterIsInstance<Mob>().first()
    first.w.setOwner(rat, ann)
    val gem = first.w.spawnItem(P.GEM, ItemLocation.Carried(rat.id))
    first.w.quit(ann)
    first.save()
    first.close()

    DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
      c.createStatement().use { st -> st.executeUpdate("DELETE FROM players WHERE name = 'Ann'") }
    }

    val second = PersistWorld(file, fresh = true)
    assertContains(
      second.report.notes,
      "pet ${rat.proto.label} #${rat.id.v} dropped: its owner is gone",
    )
    second.save()
    second.close()

    assertEquals(0, count(file, "SELECT count(*) FROM world_entities WHERE id = ${rat.id.v}"))
    assertEquals(0, count(file, "SELECT count(*) FROM world_entities WHERE id = ${gem.id.v}"))
  }

  @Test
  fun `a fresh start keeps the held pets of existing characters`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val ann = first.login("Ann")
    val rat = first.w.actors.filterIsInstance<Mob>().first()
    first.w.setOwner(rat, ann)
    first.w.quit(ann)
    first.save()
    first.close()

    val second = PersistWorld(file, fresh = true)
    second.save()
    val ann2 = second.login("Ann")
    val pet = assertNotNull(second.w.actor(rat.id))
    assertEquals(ann2.room, pet.room)
    second.close()
  }

  @Test
  fun `pet prototype removed drops pet at login with a note and player still logs in`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val ann = first.login("Ann")
    val rat = first.w.actors.filterIsInstance<Mob>().first()
    first.w.setOwner(rat, ann)
    first.w.quit(ann)
    first.save()
    first.close()

    val second = PersistWorld(file, P.protos(withRat = false))
    val ann2 = second.login("Ann")
    assertNotNull(ann2)
    assertNull(second.w.actor(rat.id))
    second.close()
  }

  @Test
  fun `two pets come back in ascending id`() {
    val file = tempDb()
    val first = PersistWorld(file)
    val ann = first.login("Ann")
    val mobs = first.w.actors.filterIsInstance<Mob>().sortedBy { it.id }
    val (rat1, rat2) = mobs.take(2)
    first.w.setOwner(rat2, ann)
    first.w.setOwner(rat1, ann)
    first.w.quit(ann)
    first.save()
    first.close()

    val second = PersistWorld(file)
    val ann2 = second.login("Ann")
    val pets = second.w.petsOf(ann2)
    assertEquals(listOf(rat1.id, rat2.id).sorted(), pets.map { it.id })
    second.close()
  }
}
