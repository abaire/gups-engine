package gups.persist

import gups.core.Label
import gups.core.fixtures.tempDb
import gups.core.model.FactionProto
import gups.core.model.Mob
import gups.core.model.StandingHolder
import gups.core.model.StandingSubject
import gups.core.world.applyStanding
import gups.core.world.petsOf
import gups.core.world.quit
import gups.core.world.setOwner
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandingPersistenceTest {
  private val factionTown = Label("//t:townsfolk")
  private val factionGoblin = Label("//t:goblins")
  private val factionExtinct = Label("//t:extinct")

  private fun protosWithFactions(vararg factions: FactionProto) =
    P.protos(extraFactions = factions.toList())

  @Test
  fun `faction and personal standing records survive checkpoint and restore`() {
    val protos = protosWithFactions(FactionProto(factionTown), FactionProto(factionGoblin))
    val file = tempDb()
    val a = PersistWorld(file, protos = protos)
    val ann = a.login("Ann")
    val rat = a.w.actors.filterIsInstance<Mob>().first()

    // Faction to faction record
    a.w.applyStanding(
      StandingHolder.Faction(factionTown),
      StandingSubject.Faction(factionGoblin),
      25,
      "treaty",
    )
    // Faction to actor record
    a.w.applyStanding(StandingHolder.Faction(factionTown), StandingSubject.Of(ann.id), 40, "help")
    // Personal mob record
    a.w.applyStanding(StandingHolder.Personal(rat), StandingSubject.Of(ann.id), -30, "attack")

    a.save()
    a.close()

    val b = PersistWorld(file, protos = protos)
    val ann2 = b.login("Ann")
    val rat2 = b.w.actors.filterIsInstance<Mob>().first { it.id == rat.id }

    val facFac = b.w.factionStanding[factionTown to StandingSubject.Faction(factionGoblin)]
    assertNotNull(facFac)
    assertEquals(25, facFac.amount)
    assertEquals(1, facFac.history.size)
    assertEquals("treaty", facFac.history.first().event)

    val facAct = b.w.factionStanding[factionTown to StandingSubject.Of(ann2.id)]
    assertNotNull(facAct)
    assertEquals(40, facAct.amount)
    assertEquals(1, facAct.history.size)
    assertEquals("help", facAct.history.first().event)

    val personal = rat2.personalStanding[ann2.id]
    assertNotNull(personal)
    assertEquals(-30, personal.amount)
    assertEquals(1, personal.history.size)
    assertEquals("attack", personal.history.first().event)

    b.close()
  }

  @Test
  fun `history is pruned at checkpoint`() {
    val protos = protosWithFactions(FactionProto(factionTown))
    val file = tempDb()
    val a = PersistWorld(file, protos = protos)
    val ann = a.login("Ann")

    a.w.applyStanding(StandingHolder.Faction(factionTown), StandingSubject.Of(ann.id), 20, "recent")
    // Manually add old history entry
    val rec = a.w.factionStanding[factionTown to StandingSubject.Of(ann.id)]!!
    rec.history.addFirst(
      gups.core.model.StandingChange(pulse = -20000L, amount = 10, event = "ancient", via = null)
    )

    a.save()
    a.close()

    val b = PersistWorld(file, protos = protos)
    val ann2 = b.login("Ann")
    val restored = b.w.factionStanding[factionTown to StandingSubject.Of(ann2.id)]
    assertNotNull(restored)
    assertEquals(1, restored.history.size)
    assertEquals("recent", restored.history.first().event)

    b.close()
  }

  @Test
  fun `stored pet keeps its personal standing records`() {
    val file = tempDb()
    val a = PersistWorld(file)
    val ann = a.login("Ann")
    val pet = a.w.actors.filterIsInstance<Mob>().first()
    a.w.setOwner(pet, ann)

    a.w.applyStanding(StandingHolder.Personal(pet), StandingSubject.Of(ann.id), 50, "fond")
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file)
    val ann2 = b.login("Ann")
    val restoredPet = b.w.petsOf(ann2).single()
    val standing = restoredPet.personalStanding[ann2.id]
    assertNotNull(standing)
    assertEquals(50, standing.amount)
    assertEquals("fond", standing.history.first().event)

    b.close()
  }

  @Test
  fun `faction records about a stored pet survive while its owner is offline`() {
    val protos = protosWithFactions(FactionProto(factionTown))
    val file = tempDb()
    val a = PersistWorld(file, protos = protos)
    val ann = a.login("Ann")
    val pet = a.w.actors.filterIsInstance<Mob>().first()
    a.w.setOwner(pet, ann)
    a.w.applyStanding(StandingHolder.Faction(factionTown), StandingSubject.Of(pet.id), -40, "kill")
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, protos = protos)
    assertEquals(-40, b.w.factionStanding[factionTown to StandingSubject.Of(pet.id)]?.amount)
    b.save()
    b.close()

    val c = PersistWorld(file, protos = protos)
    assertEquals(-40, c.w.factionStanding[factionTown to StandingSubject.Of(pet.id)]?.amount)
    c.close()
  }

  @Test
  fun `removed faction records are dropped on restore with note`() {
    val protosWithExtinct =
      protosWithFactions(FactionProto(factionTown), FactionProto(factionExtinct))
    val file = tempDb()
    val a = PersistWorld(file, protos = protosWithExtinct)
    a.w.applyStanding(
      StandingHolder.Faction(factionTown),
      StandingSubject.Faction(factionExtinct),
      10,
      "trade",
    )
    a.w.applyStanding(
      StandingHolder.Faction(factionExtinct),
      StandingSubject.Faction(factionTown),
      10,
      "trade",
    )
    a.save()
    a.close()

    val protosWithoutExtinct = protosWithFactions(FactionProto(factionTown))
    val b = PersistWorld(file, protos = protosWithoutExtinct)
    assertTrue(b.report.notes.any { "extinct" in it && "dropped" in it })
    assertNull(b.w.factionStanding[factionTown to StandingSubject.Faction(factionExtinct)])
    assertNull(b.w.factionStanding[factionExtinct to StandingSubject.Faction(factionTown)])

    b.close()
  }

  @Test
  fun `deleted character standing records are dropped on restore`() {
    val protos = protosWithFactions(FactionProto(factionTown))
    val file = tempDb()
    val a = PersistWorld(file, protos = protos)
    val bob = a.login("Bob")
    val bobId = bob.id
    a.w.applyStanding(StandingHolder.Faction(factionTown), StandingSubject.Of(bobId), 35, "help")
    a.w.quit(bob)
    a.save()
    a.close()

    // Delete Bob directly from SQLite
    DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
      c.createStatement().use { st -> st.executeUpdate("DELETE FROM players WHERE name = 'Bob'") }
    }

    val b = PersistWorld(file, protos = protos)
    assertNull(b.w.factionStanding[factionTown to StandingSubject.Of(bobId)])
    assertTrue(b.report.notes.any { "#${bobId.v}" in it && "dropped" in it })

    b.close()
  }
}
