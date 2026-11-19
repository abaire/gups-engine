package gups.persist

import gups.core.Label
import gups.core.WORLD_TICK_PULSES
import gups.core.fixtures.tempDb
import gups.core.model.Item
import gups.core.model.ItemLocation
import gups.core.model.ItemProto
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Prototypes
import gups.core.model.Respawn
import gups.core.model.Roll
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import gups.core.model.SpawnerProto
import gups.core.model.ZoneProto
import gups.core.world.SpawnKey
import gups.core.world.despawn
import gups.core.world.invariantViolations
import gups.core.world.moveItem
import gups.core.world.quit
import gups.core.world.savedSpawners
import gups.core.world.setOwner
import gups.core.world.spawnKeyOf
import gups.core.world.spawnerInfo
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnStatePersistenceTest {
  private val hall = Label("//t:hall")
  private val rat = Label("//t:rat")
  private val bat = Label("//t:bat")
  private val gem = Label("//t:gem")

  private fun protos(spawns: List<SpawnRule>, withBat: Boolean = true): Prototypes =
    Prototypes(
      zones = listOf(ZoneProto("t", resetTicks = Roll.of(5))),
      rooms = listOf(RoomProto(hall, "The Hall", spawns = spawns)),
      mobs =
        listOfNotNull(
          MobProto(rat, "a rat", listOf("rat")),
          MobProto(bat, "a bat", listOf("bat")).takeIf { withBat },
        ),
      items = listOf(ItemProto(gem, "a gem", listOf("gem"))),
    )

  private fun mobs(w: PersistWorld, label: Label = rat): List<Mob> =
    w.w.actors.filterIsInstance<Mob>().filter { it.protoLabel == label }

  private fun items(w: PersistWorld, label: Label = gem): List<Item> =
    w.w.allEntities.filterIsInstance<Item>().filter { it.protoLabel == label }

  @Test
  fun `a held copy and a pending rolled deadline survive a restart unchanged`() {
    val file = tempDb()
    val rules =
      listOf(
        SpawnRule(
          rat,
          id = Label("//t:rats"),
          max = 3,
          batch = Roll.of(1),
          respawn = Respawn.Timed(Roll(10, 50)),
        )
      )
    val a = PersistWorld(file, protos(rules), bubble = true)
    assertEquals(3, mobs(a).size)
    // Despawn two rats while the hall is dormant (no players logged in).
    // Force the first rolled deadline to +20 pulses and the second to +35 pulses.
    a.w.rng.force(listOf(20))
    a.w.despawn(mobs(a).first())
    a.w.despawn(mobs(a).first())
    assertEquals(1, mobs(a).size)

    // Advance one world tick: the first deadline (+20) fires, holds one copy, and rolls +35.
    a.w.rng.force(listOf(35))
    a.run(WORLD_TICK_PULSES)
    assertEquals(1, mobs(a).size)
    val savedBefore = a.w.savedSpawners("t").single()
    assertEquals(1, savedBefore.held.size)
    assertEquals(a.w.pulse + 35, savedBefore.at)
    assertTrue(savedBefore.ran)
    a.save()
    a.close()

    val b = PersistWorld(file, protos(rules), bubble = true)
    val restored = b.w.savedSpawners("t").single()
    assertEquals(savedBefore.key, restored.key)
    assertEquals(savedBefore.at, restored.at)
    assertEquals(savedBefore.ran, restored.ran)
    assertEquals(savedBefore.held, restored.held)
    assertEquals(1, mobs(b).size)

    // Logging in and stepping one pulse wakes the room and places the held copy without re-rolling.
    b.login("Ann")
    b.run(1)
    assertEquals(2, mobs(b).size)
    assertTrue(b.w.savedSpawners("t").single().held.isEmpty())

    // Advancing to the next world tick (at or after the saved deadline) brings in the third rat.
    b.run(WORLD_TICK_PULSES - 1)
    assertEquals(3, mobs(b).size)
    b.close()
  }

  @Test
  fun `a never rule does not fill again after a restart, while fresh fills it`() {
    val file = tempDb()
    val rules = listOf(SpawnRule(rat, id = Label("//t:once"), max = 2, respawn = Respawn.Never))
    val a = PersistWorld(file, protos(rules), bubble = true)
    assertEquals(2, mobs(a).size)
    mobs(a).forEach { a.w.despawn(it) }
    assertEquals(0, mobs(a).size)
    a.save()
    a.close()

    val b = PersistWorld(file, protos(rules), bubble = true)
    assertEquals(0, mobs(b).size)
    b.save()
    b.close()

    val c = PersistWorld(file, protos(rules), fresh = true)
    assertEquals(2, mobs(c).size)
    c.close()
  }

  @Test
  fun `a restored binding above a lowered max is kept`() {
    val file = tempDb()
    val a =
      PersistWorld(
        file,
        protos(listOf(SpawnRule(rat, id = Label("//t:rats"), max = 3, respawn = Respawn.Never))),
      )
    assertEquals(3, mobs(a).size)
    a.save()
    a.close()

    val lowered = listOf(SpawnRule(rat, id = Label("//t:rats"), max = 1, respawn = Respawn.Never))
    val b = PersistWorld(file, protos(lowered))
    val live = mobs(b)
    assertEquals(3, live.size)
    for (m in live) {
      assertNotNull(b.w.spawnKeyOf(m))
    }
    b.close()
  }

  @Test
  fun `a rule that is new since the save does its first fill`() {
    val file = tempDb()
    val initial = listOf(SpawnRule(rat, id = Label("//t:rats"), max = 1, respawn = Respawn.Never))
    val a = PersistWorld(file, protos(initial))
    mobs(a, rat).forEach { a.w.despawn(it) }
    a.save()
    a.close()

    val expanded =
      initial + SpawnRule(bat, id = Label("//t:bats"), max = 2, respawn = Respawn.Never)
    val b = PersistWorld(file, protos(expanded))
    assertEquals(0, mobs(b, rat).size)
    assertEquals(2, mobs(b, bat).size)
    b.close()
  }

  @Test
  fun `two unnamed rules for the same label in a room bind back to the right ones`() {
    val file = tempDb()
    val rules =
      listOf(
        SpawnRule(rat, max = 1, respawn = Respawn.Never, atIndex = 0),
        SpawnRule(rat, max = 1, respawn = Respawn.Timed(Roll.of(600)), atIndex = 0),
      )
    val a = PersistWorld(file, protos(rules), bubble = true)
    assertEquals(2, mobs(a).size)
    val byKey = mobs(a).associateBy { a.w.spawnKeyOf(it)!! }
    assertEquals(2, byKey.size)
    val keys = byKey.keys.toList()
    assertNotEquals(keys[0], keys[1])
    a.save()
    a.close()

    val b = PersistWorld(file, protos(rules), bubble = true)
    assertEquals(2, mobs(b).size)
    for ((key, oldMob) in byKey) {
      val restored = b.w.actor(oldMob.id) as Mob
      assertEquals(key, b.w.spawnKeyOf(restored))
    }
    b.close()
  }

  @Test
  fun `an unknown saved key is ignored without error`() {
    val file = tempDb()
    val rules =
      listOf(
        SpawnRule(rat, id = Label("//t:keep"), max = 1, respawn = Respawn.Never),
        SpawnRule(bat, id = Label("//t:drop"), max = 1, respawn = Respawn.Timed(Roll.of(300))),
      )
    val a = PersistWorld(file, protos(rules), bubble = true)
    // Despawn bat so the dropped rule has a pending deadline saved in zone_states.
    a.w.despawn(mobs(a, bat).single())
    a.save()
    a.close()

    val remaining = listOf(SpawnRule(rat, id = Label("//t:keep"), max = 1, respawn = Respawn.Never))
    val b = PersistWorld(file, protos(remaining))
    assertEquals(1, mobs(b, rat).size)
    assertEquals(0, mobs(b, bat).size)
    b.close()
  }

  @Test
  fun `duplicate spawn rule ids fail world construction`() {
    val file = tempDb()
    val dup =
      listOf(
        SpawnRule(rat, id = Label("//t:dup"), max = 1),
        SpawnRule(bat, id = Label("//t:dup"), max = 1),
      )
    val err = assertFailsWith<IllegalArgumentException> { PersistWorld(file, protos(dup)) }
    assertEquals("duplicate spawn rule id //t:dup", err.message)
  }

  @Test
  fun `gone copy held by logged-out player counts as away across restart and rebinds at login`() {
    val file = tempDb()
    val rules =
      listOf(
        SpawnRule(
          gem,
          id = Label("//t:gems"),
          max = 1,
          respawn = Respawn.Timed(Roll.of(WORLD_TICK_PULSES)),
        )
      )
    val a = PersistWorld(file, protos(rules))
    val copy = items(a).single()
    val ann = a.login("Ann")
    a.w.moveItem(copy, ItemLocation.Carried(ann.id))
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, protos(rules))
    // The copy is away, so the rule does not refill on restart or after world ticks.
    assertTrue(items(b).isEmpty())
    b.run(WORLD_TICK_PULSES * 2)
    assertTrue(items(b).isEmpty())

    // At login, Ann brings the item back and it rebinds to the rule.
    val ann2 = b.login("Ann")
    val restored = ann2.inventory.single()
    assertEquals(copy.id, restored.id)
    assertEquals(
      Label("//t:gems"),
      (b.w.spawnKeyOf(restored) as? gups.core.world.SpawnKey.Named)?.id,
    )
    b.close()
  }

  @Test
  fun `fresh restart fills item rule and logged-in player item comes back unbound`() {
    val file = tempDb()
    val rules =
      listOf(
        SpawnRule(
          gem,
          id = Label("//t:gems"),
          max = 1,
          respawn = Respawn.Timed(Roll.of(WORLD_TICK_PULSES)),
        )
      )
    val a = PersistWorld(file, protos(rules))
    val copy = items(a).single()
    val ann = a.login("Ann")
    a.w.moveItem(copy, ItemLocation.Carried(ann.id))
    a.w.quit(ann)
    a.save()
    a.close()

    val b = PersistWorld(file, protos(rules), fresh = true)
    // Fresh fills the room's rule immediately.
    assertEquals(1, b.w.room(hall).items.count { it.protoLabel == gem })

    // Ann logs in with her saved gem, which is now unbound.
    val ann2 = b.login("Ann")
    val restored = ann2.inventory.single()
    assertNull(b.w.spawnKeyOf(restored))
    b.close()
  }

  @Test
  fun `a held copy whose room is gone is dropped at restart`() {
    val file = tempDb()
    val vault = Label("//t:vault")
    val pool =
      SpawnerProto(
        Label("//t:pool"),
        SpawnRule(rat, id = Label("//t:pool"), respawn = Respawn.Timed(Roll.of(10))),
        rooms = mapOf(vault to 1),
      )
    fun world(rooms: List<RoomProto>) =
      Prototypes(
        zones = listOf(ZoneProto("t")),
        rooms = rooms,
        mobs = listOf(MobProto(rat, "a rat", listOf("rat"))),
        spawners = listOf(pool),
      )
    val hallRoom = RoomProto(hall, "The Hall")
    val a =
      PersistWorld(file, world(listOf(hallRoom, RoomProto(vault, "The Vault"))), bubble = true)
    a.w.despawn(mobs(a).single())
    a.run(WORLD_TICK_PULSES)
    assertEquals(1, a.w.savedSpawners("t").single().held.size)
    a.save()
    a.close()

    val b = PersistWorld(file, world(listOf(hallRoom)), bubble = true)
    val saved = b.w.savedSpawners("t").single()
    assertTrue(saved.held.isEmpty())
    assertEquals(b.w.pulse + 10, saved.at)
    b.close()
  }

  @Test
  fun `a saved deadline is not restored onto a rule that is no longer timed`() {
    val file = tempDb()
    val timed =
      listOf(SpawnRule(rat, id = Label("//t:rats"), respawn = Respawn.Timed(Roll.of(300))))
    val a = PersistWorld(file, protos(timed))
    a.w.despawn(mobs(a).single())
    assertNotNull(a.w.savedSpawners("t").single().at)
    a.save()
    a.close()

    val reset = listOf(SpawnRule(rat, id = Label("//t:rats"), respawn = Respawn.Reset))
    val b = PersistWorld(file, protos(reset))
    assertNull(b.w.savedSpawners("t").single().at)
    b.close()
  }

  @Test
  fun `a saved mob binding is not restored onto a rule that now places items`() {
    val file = tempDb()
    val a = PersistWorld(file, protos(listOf(SpawnRule(rat, id = Label("//t:x")))))
    val rat1 = mobs(a).single()
    a.save()
    a.close()

    val b = PersistWorld(file, protos(listOf(SpawnRule(gem, id = Label("//t:x")))))
    assertNull(b.w.spawnKeyOf(b.w.actor(rat1.id) as Mob))
    assertEquals(emptyList(), b.w.invariantViolations().map { it.message })
    b.close()
  }

  @Test
  fun `an away copy that does not come back at login stops counting`() {
    val file = tempDb()
    val rules =
      listOf(
        SpawnRule(
          gem,
          id = Label("//t:gems"),
          max = 1,
          respawn = Respawn.Timed(Roll.of(WORLD_TICK_PULSES)),
        )
      )
    val a = PersistWorld(file, protos(rules))
    val copy = items(a).single()
    val ann = a.login("Ann")
    val pet = a.w.spawnMob(bat, a.w.room(hall), 0)
    a.w.setOwner(pet, ann)
    a.w.moveItem(copy, ItemLocation.Carried(pet.id))
    a.w.quit(ann)
    a.save()
    a.close()

    // The pet's prototype is gone, so at login the pet and the copy it carried are dropped.
    val b = PersistWorld(file, protos(rules, withBat = false))
    val key = SpawnKey.Named(Label("//t:gems"))
    assertEquals(1, b.w.spawnerInfo(key).live)
    b.login("Ann")
    assertNull(b.w.item(copy.id))
    assertEquals(0, b.w.spawnerInfo(key).live)
    b.run(WORLD_TICK_PULSES)
    assertEquals(1, b.w.room(hall).items.count { it.protoLabel == gem })
    b.close()
  }

  @Test
  fun `item row held by a deleted character is not counted as away`() {
    val file = tempDb()
    val rules =
      listOf(
        SpawnRule(
          gem,
          id = Label("//t:gems"),
          max = 1,
          respawn = Respawn.Timed(Roll.of(WORLD_TICK_PULSES)),
        )
      )
    val a = PersistWorld(file, protos(rules))
    val copy = items(a).single()
    val ann = a.login("Ann")
    a.w.moveItem(copy, ItemLocation.Carried(ann.id))
    a.w.quit(ann)
    a.save()
    a.close()

    DriverManager.getConnection("jdbc:sqlite:$file").use { conn ->
      conn.createStatement().use { st ->
        st.executeUpdate("DELETE FROM players WHERE name = 'Ann'")
      }
    }

    val b = PersistWorld(file, protos(rules))
    // The orphaned item is not counted as away, so the rule is short and refills after its timer.
    assertTrue(b.w.room(hall).items.isEmpty())
    b.run(WORLD_TICK_PULSES)
    assertEquals(1, b.w.room(hall).items.count { it.protoLabel == gem })
    b.close()
  }
}
