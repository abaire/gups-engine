package gups.core.world

import gups.core.model.Band
import gups.core.model.DoorState
import gups.core.model.ItemLocation
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackTest {
  @Test
  fun `bands group track distance`() {
    assertEquals(Band.MELEE, Band.between(2, 2))
    assertEquals(Band.NEAR, Band.between(1, 2))
    assertEquals(Band.FAR, Band.between(0, 4))
    assertTrue(Band.FAR.reaches(Band.NEAR))
    assertFalse(Band.MELEE.reaches(Band.NEAR))
  }

  @Test
  fun `look groups actors by band, never by raw index`() {
    val f = Fixture()
    val p = f.player("Ann", index = 1)
    f.mob(index = 1)
    f.mob(T.DUMMY, index = 4)
    f.input(p, "look")
    val out = f.out(p)
    assertContains(out, "Beside you: a goblin.")
    assertContains(out, "Far off: a training dummy.")
    assertContains(out, "Exits: north, east, south (locked).")
  }

  @Test
  fun `advance steps toward the target and is a no-op when already there`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.mob(index = 2)
    f.input(p, "advance goblin")
    f.run()
    assertEquals(1, p.trackIndex)
    assertEquals(2, p.waitState)
    f.run(2)
    f.input(p, "advance goblin")
    f.run()
    f.out(p)
    f.run(2)
    f.input(p, "advance goblin")
    f.run()
    assertEquals("You're already beside a goblin.\n", f.out(p))
    assertEquals(0, p.waitState)
  }

  @Test
  fun `retreat moves away, and is impossible at an edge or in a cramped room`() {
    val f = Fixture()
    val p = f.player("Ann", index = 1)
    f.mob(index = 2)
    f.input(p, "retreat from goblin")
    f.run()
    assertEquals(0, p.trackIndex)
    assertEquals(2, p.waitState)
    f.run(2)
    f.out(p)
    f.input(p, "retreat from goblin")
    f.run()
    assertEquals("You can't retreat any further.\n", f.out(p))
    assertEquals(0, p.waitState)

    val q = f.player("Bea", at = T.CLOSET)
    f.mob(at = T.CLOSET)
    f.input(q, "retreat from goblin")
    f.run()
    assertEquals("You can't retreat any further.\n", f.out(q))
  }

  @Test
  fun `retreat without a target leaves the nearest opponent toward more room`() {
    val f = Fixture()
    val p = f.player("Ann", index = 1)
    val g = f.mob(index = 3)
    f.w.startCombat(p, g)
    f.input(p, "retreat")
    f.run()
    assertEquals(0, p.trackIndex)
    assertEquals(
      1,
      RetreatAction.step(1, 1, 5),
    ) // shared index: toward the back, which has more room
    assertEquals(-1, RetreatAction.step(3, 3, 5))
    assertNull(RetreatAction.step(0, 0, 1))
  }

  @Test
  fun `a pinned actor cannot walk out, and retreat needs a check`() {
    val rules = FixedRules(toHit = 0, retreat = 0)
    val f = Fixture(rules = rules)
    val p = f.player("Ann")
    val g = f.mob()
    f.w.startCombat(p, g)
    f.input(p, "north")
    f.run()
    assertContains(f.out(p), "pinned")
    assertEquals(T.HALL, p.room.label)
    assertEquals(0, p.waitState)

    f.input(p, "retreat")
    f.run()
    assertContains(f.out(p), "You fail to break away!")
    assertEquals(0, p.trackIndex)
    assertEquals(4, p.waitState)

    rules.retreat = 100
    f.run(4)
    f.input(p, "retreat")
    f.run()
    assertEquals(1, p.trackIndex)
    // No longer pinned: an exit works from any index.
    f.run(2)
    f.input(p, "north")
    f.run()
    assertEquals(T.YARD, p.room.label)
    assertFalse(p.isEngaged, "leaving the room ends engagement")
    assertFalse(g.isEngaged)
  }

  @Test
  fun `flee ignores pinning, and failure lags`() {
    val rules = FixedRules(toHit = 0, flee = 0)
    val f = Fixture(rules = rules)
    val p = f.player("Ann")
    f.w.startCombat(p, f.mob())
    f.input(p, "flee")
    f.run()
    assertContains(f.out(p), "You fail to escape!")
    assertEquals(4, p.waitState)
    rules.flee = 100
    f.run(4)
    f.input(p, "flee")
    f.run()
    // The vault door is locked, so the only ways out are north and east.
    assertTrue(p.room.label == T.YARD || p.room.label == T.CLOSET)
    assertFalse(p.isEngaged)
  }

  @Test
  fun `arrivals use the exit's entry index, clamped to the destination`() {
    val f = Fixture()
    val p = f.player("Ann", index = 3)
    f.input(p, "north")
    f.run()
    assertEquals(0, p.trackIndex)
    f.input(p, "south")
    f.run()
    assertEquals(T.HALL, p.room.label)
    assertEquals(4, p.trackIndex, "entry index -1 is the back of the hall")
    f.input(p, "east")
    f.run()
    assertEquals(T.CLOSET, p.room.label)
    assertEquals(0, p.trackIndex, "entry index 2 is clamped in a cramped room")
  }

  @Test
  fun `movement costs move points`() {
    val f = Fixture()
    val p = f.player("Ann")
    p.move = 0
    f.input(p, "north")
    f.run()
    assertEquals("You are too exhausted.\n", f.out(p))
    p.move = 5
    f.input(p, "north")
    f.run()
    assertEquals(4, p.move)
  }

  @Test
  fun `doors block passage until unlocked and opened, on both sides`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "south")
    f.run()
    assertEquals("The way south is closed.\n", f.out(p))
    f.input(p, "unlock south")
    f.run()
    assertEquals("You don't have the key.\n", f.out(p))
    f.w.spawnItem(T.KEY, ItemLocation.Carried(p.id))
    f.input(p, "unlock south")
    f.input(p, "open s")
    f.input(p, "south")
    f.run(3)
    assertEquals(T.VAULT, p.room.label)
    assertEquals(DoorState.OPEN, f.w.room(T.VAULT).exits.values.single().door!!.state)
  }

  @Test
  fun `get lag depends on the band, and far items are out of reach`() {
    val f = Fixture()
    val p = f.player("Ann")
    val hall = f.w.room(T.HALL)
    f.w.spawnItem(T.KEY, ItemLocation.InRoom(hall.id, 0))
    f.w.spawnItem(T.SWORD, ItemLocation.InRoom(hall.id, 1))
    f.w.spawnItem(T.BOW, ItemLocation.InRoom(hall.id, 4))
    f.input(p, "get key")
    f.run()
    assertEquals(0, p.waitState)
    f.input(p, "get sword")
    f.run()
    assertEquals(2, p.waitState)
    f.run(2)
    f.out(p)
    f.input(p, "get bow")
    f.run()
    assertEquals("You can't reach a short bow from here.\n", f.out(p))
    assertEquals(listOf("a brass key", "a sword"), p.inventory.map { it.name })

    f.input(p, "drop sword")
    f.run()
    assertEquals(
      ItemLocation.InRoom(hall.id, 0),
      hall.items.first { it.name == "a sword" }.location,
    )
  }
}
