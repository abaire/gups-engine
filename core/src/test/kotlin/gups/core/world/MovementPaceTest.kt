package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureSchemas
import gups.core.model.Direction
import gups.core.model.ItemLocation
import gups.core.model.Mob
import gups.core.model.MobProto
import gups.core.model.Player
import gups.core.model.Prototypes
import gups.core.rules.RulesContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [FixedRules] that pins `move_pace` (and optionally `flee_chance`). */
class PaceRules(val pace: Int, flee: Int? = null) : FixedRules(flee = flee) {
  override fun movePace(c: RulesContext): Int? = pace
}

/**
 * Walking pace: each move adds `move_pace` to a movement debt, and an actor moves only while the
 * debt is below 100. Fast actors make up to four complete moves a pulse.
 */
class MovementPaceTest {
  /** The corridor and its schema, reused across the tests. */
  private fun protos() = C.protos(n = 10)

  private fun fixture(
    pace: Int,
    flee: Int? = null,
    features: Features = Features.ALL,
    config: (PaceRules) -> Unit = {},
  ): Fixture {
    val rules = PaceRules(pace, flee).also(config)
    return Fixture(rules = rules, protos = protos(), features = features)
  }

  @Test
  fun `default pace walks one room per pulse`() {
    val f = Fixture(rules = FixedRules(), protos = protos())
    val p = f.player("Ann", at = C.r(0))
    repeat(5) { f.input(p, "east") }
    f.run()
    assertEquals(C.r(1), p.room.label, "pulse 1")
    f.run(4)
    assertEquals(C.r(5), p.room.label, "pulse 5")
  }

  @Test
  fun `pace 200 walks one room every two pulses`() {
    val f = fixture(pace = 200)
    val p = f.player("Ann", at = C.r(0))
    repeat(3) { f.input(p, "east") }
    f.run()
    assertEquals(C.r(1), p.room.label, "pulse 1")
    f.run()
    assertEquals(C.r(1), p.room.label, "pulse 2 held")
    f.run()
    assertEquals(C.r(2), p.room.label, "pulse 3")
    f.run()
    assertEquals(C.r(2), p.room.label, "pulse 4 held")
    f.run()
    assertEquals(C.r(3), p.room.label, "pulse 5")
  }

  @Test
  fun `pace 150 carries fractions across pulses`() {
    val f = fixture(pace = 150)
    val p = f.player("Ann", at = C.r(0))
    repeat(5) { f.input(p, "east") }
    f.run()
    assertEquals(C.r(1), p.room.label, "pulse 1")
    f.run()
    assertEquals(C.r(2), p.room.label, "pulse 2")
    f.run()
    assertEquals(C.r(2), p.room.label, "pulse 3 held")
    f.run()
    assertEquals(C.r(3), p.room.label, "pulse 4")
    f.run()
    assertEquals(C.r(4), p.room.label, "pulse 5")
    f.run()
    assertEquals(C.r(4), p.room.label, "pulse 6 held")
    f.run()
    assertEquals(C.r(5), p.room.label, "pulse 7")
  }

  @Test
  fun `pace 50 makes two moves a pulse`() {
    val f = fixture(pace = 50)
    val p = f.player("Ann", at = C.r(0))
    repeat(4) { f.input(p, "east") }
    f.run()
    assertEquals(C.r(2), p.room.label, "pulse 1: two moves")
    f.run()
    assertEquals(C.r(4), p.room.label, "pulse 2: two more")
  }

  @Test
  fun `a pace below the clamp becomes four moves a pulse`() {
    val f = fixture(pace = 10)
    val p = f.player("Ann", at = C.r(0))
    repeat(8) { f.input(p, "east") }
    f.run()
    assertEquals(C.r(4), p.room.label, "pulse 1: four moves")
    f.run()
    assertEquals(C.r(8), p.room.label, "pulse 2: four more")
  }

  @Test
  fun `a guard on the exit out of r2 stops the walk in r2`() {
    val protos = protos()
    val warden =
      MobProto(
        Label("//c:warden"),
        "a warden",
        listOf("warden"),
        vitals = mapOf(FixtureSchemas.current.health to 40),
        behaviors = listOf(Guard(Direction.EAST)),
      )
    val f =
      Fixture(
        rules = PaceRules(25),
        protos =
          Prototypes(
            protos.rooms.values.toList(),
            protos.mobs.values.toList() + warden,
            zones = protos.zones.values.toList(),
          ),
      )
    f.w.spawnMob(warden.label, f.w.room(C.r(2)))
    val p = f.player("Ann", at = C.r(0))
    repeat(6) { f.input(p, "east") }
    f.run(3)
    assertEquals(C.r(2), p.room.label, "the warden holds the exit east")
    assertContains(f.out(p), "blocks your way")
  }

  @Test
  fun `two moves in one pulse each spend move_cost`() {
    val f = fixture(pace = 50) { it.moveCostOf = { mapOf(FixtureSchemas.current.health to 1) } }
    val p = f.player("Ann", at = C.r(0))
    repeat(4) { f.input(p, "east") }
    f.run()
    assertEquals(C.r(2), p.room.label)
    assertEquals(48, p.current(f.fs.health), "two moves spent two health")
  }

  @Test
  fun `an onlooker in r1 sees the walker arrive and leave`() {
    val f = fixture(pace = 25)
    val p = f.player("Ann", at = C.r(0))
    val onlooker = f.player("Bob", at = C.r(1))
    repeat(3) { f.input(p, "east") }
    f.run()
    val seen = f.out(onlooker)
    assertContains(seen, "arrives.")
    assertContains(seen, "leaves east.")
  }

  @Test
  fun `the debt holds back only moves, not other actions`() {
    val f = fixture(pace = 300)
    val p = f.player("Ann", at = C.r(0))
    f.w.spawnItem(C.CRUMB, ItemLocation.InRoom(f.w.room(C.r(1)).id, 0))
    f.input(p, "east")
    f.input(p, "get crumb")
    f.run()
    assertEquals(C.r(1), p.room.label)
    assertEquals(300L, f.w.moveDebt(p), "the walk charged debt the get ignores")
    f.run()
    assertTrue(p.inventory.any { it.protoLabel == C.CRUMB }, "the get ran despite the debt")
  }

  @Test
  fun `a non-move first step leaves no room for an extra move that pulse`() {
    val f = fixture(pace = 50)
    val p = f.player("Ann", at = C.r(0))
    f.w.spawnItem(C.CRUMB, ItemLocation.InRoom(f.w.room(C.r(0)).id, 0))
    f.input(p, "get crumb")
    f.input(p, "east")
    f.run()
    assertEquals(C.r(0), p.room.label, "the east waits for pulse 2")
    assertTrue(p.inventory.any { it.protoLabel == C.CRUMB })
    f.run()
    assertEquals(C.r(1), p.room.label)
  }

  @Test
  fun `a successful flee charges movement debt and a queued move waits`() {
    val f = fixture(pace = 200, flee = 100)
    val p = f.player("Ann", at = C.r(0))
    f.w.spawnMob(C.RAT, f.w.room(C.r(0)))
    f.w.startCombat(p, f.w.room(C.r(0)).actorIndex.values.filterIsInstance<Mob>().single())
    f.input(p, "flee")
    f.input(p, "east")
    f.run()
    assertEquals(C.r(1), p.room.label, "the flee moved the player")
    assertEquals(200L, f.w.moveDebt(p), "the flee charged debt like a walk")
    f.run()
    assertEquals(C.r(1), p.room.label, "the east waits")
    f.run()
    assertEquals(C.r(2), p.room.label, "the east runs once the debt clears")
  }

  @Test
  fun `a failed flee charges no movement debt`() {
    val f = fixture(pace = 200, flee = 0)
    val p = f.player("Ann", at = C.r(0))
    f.w.spawnMob(C.RAT, f.w.room(C.r(0)))
    f.w.startCombat(p, f.w.room(C.r(0)).actorIndex.values.filterIsInstance<Mob>().single())
    f.input(p, "flee")
    f.run()
    assertEquals(C.r(0), p.room.label, "the flee failed")
    assertEquals(0L, f.w.moveDebt(p), "a failed flee adds no debt")
  }

  @Test
  fun `teleport adds no movement debt`() {
    val f = fixture(pace = 50)
    val p = f.player("Ann", at = C.r(0))
    f.w.teleport(p, f.w.room(C.r(5)))
    f.run()
    assertEquals(0L, f.w.moveDebt(p), "teleport adds no debt")
    f.input(p, "east")
    f.input(p, "east")
    f.run()
    assertEquals(C.r(7), p.room.label, "no debt means two moves this pulse")
  }

  @Test
  fun `a walk pinned in its own tier is cancelled, in either id order`() {
    for (walkerFirst in listOf(true, false)) {
      val f = fixture(pace = 100) { it.moveCostOf = { mapOf(FixtureSchemas.current.stamina to 5) } }
      val walker: Player
      val killer: Player
      if (walkerFirst) {
        walker = f.player("Ann", at = C.r(0))
        killer = f.player("Bob", at = C.r(0))
      } else {
        killer = f.player("Ann", at = C.r(0))
        walker = f.player("Bob", at = C.r(0))
      }
      f.input(walker, "east")
      f.input(killer, "kill ${walker.name}")
      val stamina = walker.current(f.fs.stamina)
      f.run()
      assertEquals(C.r(0), walker.room.label, "pinned, the walker stays")
      assertEquals(stamina, walker.current(f.fs.stamina), "no move_cost spent")
      assertEquals(0L, f.w.moveDebt(walker), "no movement debt")
      assertContains(f.out(walker), "pinned")
    }
  }

  @Test
  fun `a flee is never cancelled by pinning`() {
    val f = fixture(pace = 200, flee = 100)
    val a = f.player("Ann", at = C.r(0))
    val c = f.w.spawnMob(C.RAT, f.w.room(C.r(0)))
    f.w.startCombat(a, c)
    f.input(a, "flee")
    f.run()
    assertTrue(a.room.label != C.r(0), "the flee left the room")
    assertEquals(200L, f.w.moveDebt(a), "the flee charged debt")
  }

  @Test
  fun `without track a contested walk goes through`() {
    val f = fixture(pace = 100, features = Features(Features.ALL.enabled - Feature.TRACK))
    val a = f.player("Ann", at = C.r(0))
    val b = f.player("Bob", at = C.r(0))
    f.input(a, "east")
    f.input(b, "kill Ann")
    f.run()
    assertEquals(C.r(1), a.room.label, "nothing pins without the track feature")
  }
}
