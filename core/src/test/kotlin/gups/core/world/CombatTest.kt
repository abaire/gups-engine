package gups.core.world

import gups.core.Label
import gups.core.model.ItemLocation
import gups.core.model.RoomProto
import gups.core.model.SpawnRule
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CombatTest {
  @Test
  fun `kill engages, and auto-attacks swing once per round independent of lag`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY)
    f.input(p, "kill dummy")
    f.run()
    assertTrue(p.isEngaged && g.isEngaged)
    p.waitState = 20
    f.runTo(16)
    assertEquals(1000 - 6, g.hp)
    assertEquals(50 - 6, p.hp)
    assertContains(f.out(p), "You hit a training dummy.")
  }

  @Test
  fun `out-of-reach targets are not swung at, but a ranged weapon reaches far`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    val g = f.mob(T.DUMMY, index = 4)
    f.w.startCombat(p, g)
    f.runTo(8)
    assertContains(f.out(p), "A training dummy is out of reach.")
    assertEquals(1000, g.hp)
    f.w.moveItem(
      f.w.spawnItem(T.BOW, ItemLocation.Carried(p.id)),
      ItemLocation.Equipped(p.id, "wield"),
    )
    f.runTo(16)
    assertEquals(1000 - 3, g.hp)
  }

  @Test
  fun `a casting actor does not auto-attack`() {
    val f = Fixture(rules = FixedRules(damage = 3))
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    val g = f.mob(T.DUMMY)
    f.w.startCombat(p, g)
    f.runTo(5)
    f.input(p, "cast bolt dummy") // warms up through pulse 8
    f.runTo(8)
    assertTrue(p.isCasting)
    assertEquals(1000, g.hp)
  }

  @Test
  fun `a mob death leaves a corpse with inventory and loot, awards xp, and respawns`() {
    val den = Label("//t:den")
    val protos =
      T.protos(
        extraRooms =
          listOf(
            RoomProto(
              den,
              "A Den",
              spawns = listOf(SpawnRule(T.GOBLIN, count = 1, respawnPulses = 100)),
            )
          )
      )
    val f = Fixture(rules = FixedRules(damage = 20), protos = protos)
    val g = f.w.room(den).actors.single()
    f.w.spawnItem(T.SWORD, ItemLocation.Carried(g.id))
    val p = f.player("Ann", at = den)
    f.w.startCombat(p, g)
    f.runTo(8)
    assertFalse(g.inWorld)
    val corpse = f.w.room(den).items.single()
    assertEquals("the corpse of a goblin", corpse.name)
    assertEquals(listOf("a brass key", "a sword"), corpse.contents.map { it.name }.sorted())
    assertEquals(100L, p.xp)
    assertNotNull(corpse.decayAt)

    f.runTo(120) // first world tick after the respawn deadline
    val fresh = f.w.room(den).actors.filter { it.name == "a goblin" }
    assertEquals(1, fresh.size)

    f.input(p, "get key from corpse")
    f.run()
    assertEquals(listOf("a brass key"), p.inventory.map { it.name })
  }

  @Test
  fun `corpses decay and drop what is left`() {
    val f = Fixture(rules = FixedRules(damage = 20))
    val p = f.player("Ann")
    val g = f.mob()
    f.w.startCombat(p, g)
    f.runTo(8)
    val corpse = f.w.room(T.HALL).items.single()
    f.runTo(corpse.decayAt!! + 120)
    assertEquals(listOf("a brass key"), f.w.room(T.HALL).items.map { it.name })
  }

  @Test
  fun `a player death respawns at recall and leaves carried items in a corpse`() {
    val f = Fixture(rules = FixedRules(damage = 60, initiative = mapOf("a goblin" to 20)))
    val p = f.player("Ann")
    p.recallRoom = T.YARD
    p.xp = 500
    f.w.spawnItem(T.KEY, ItemLocation.Carried(p.id))
    val g = f.mob()
    f.w.startCombat(g, p)
    f.runTo(8)
    assertTrue(p.inWorld)
    assertEquals(T.YARD, p.room.label)
    assertEquals(1, p.hp)
    assertEquals(450L, p.xp)
    assertTrue(p.inventory.isEmpty())
    assertEquals("the corpse of Ann", f.w.room(T.HALL).items.single().name)
    assertFalse(g.isEngaged)
  }

  @Test
  fun `level ups follow xp_to_level`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.w.awardXp(p, 2500)
    assertEquals(3, p.level)
  }
}
