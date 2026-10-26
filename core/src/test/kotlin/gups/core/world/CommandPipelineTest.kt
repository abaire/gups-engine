package gups.core.world

import gups.core.model.Direction
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandPipelineTest {
  @Test
  fun `fast lane runs at once, tick lane waits for an action pulse`() {
    val f = Fixture()
    val p = f.player("Ann")
    f.input(p, "look")
    assertContains(f.out(p), "The Hall")
    f.input(p, "n")
    assertEquals(1, p.actionQueue.size)
    assertEquals(T.HALL, p.room.label)
    f.run()
    assertEquals(T.YARD, p.room.label)
    assertTrue(p.actionQueue.isEmpty())
  }

  @Test
  fun `verb lookup resolves aliases, prefixes and lanes`() {
    val f = Fixture()
    val p = f.player("Ann")
    val reg = f.w.commands
    assertEquals("inventory", reg.resolve(p, "i")?.name)
    assertEquals("inventory", reg.resolve(p, "inv")?.name)
    assertEquals("look", reg.resolve(p, "lo")?.name)
    assertEquals(Lane.TICK, reg.resolve(p, "north")?.lane)
    assertEquals(Lane.TICK, reg.resolve(p, "ne")?.lane) // a standard direction even with no exit
    assertEquals(Lane.FAST, reg.resolve(p, "say")?.lane)
    assertNull(reg.resolve(p, "xyzzy"))
    f.input(p, "xyzzy")
    assertEquals("Huh?\n", f.out(p))
  }

  @Test
  fun `room and item commands come after engine and global commands`() {
    val ran = mutableListOf<String>()
    val roomWithVerb =
      T.rooms[0].copy(
        commands = mapOf("pray" to ZoneCommand { _, a, args, _ -> ran += "${a.name}:$args" })
      )
    val f =
      Fixture(
        protos =
          gups.core.model.Prototypes(
            listOf(roomWithVerb) + T.rooms.drop(1),
            T.mobs,
            T.items,
            T.skills,
          )
      )
    val p = f.player("Ann")
    f.w.commands.registerGlobal(FastCommand("quests") { it.world.send(it.actor, "No quests.") })
    f.input(p, "quests")
    assertEquals("No quests.\n", f.out(p))
    f.input(p, "pray hard")
    assertTrue(ran.isEmpty(), "zone verbs run in the tick lane")
    f.run()
    assertEquals(listOf("Ann:hard"), ran)
  }

  @Test
  fun `the queue holds at most ten actions`() {
    val f = Fixture()
    val p = f.player("Ann")
    repeat(10) { f.input(p, "advance") }
    assertEquals(10, p.actionQueue.size)
    f.out(p)
    f.input(p, "flee")
    assertEquals("You can't plan that far ahead.\n", f.out(p))
    assertEquals(10, p.actionQueue.size)
  }

  @Test
  fun `fast lane is rate limited by a token bucket`() {
    val f = Fixture()
    val p = f.player("Ann")
    repeat(20) { f.input(p, "who") }
    f.out(p)
    f.input(p, "who")
    assertEquals("Slow down.\n", f.out(p))
    // Tick-lane commands are bounded by the queue, not the bucket.
    f.input(p, "north")
    assertEquals(1, p.actionQueue.size)
    f.nanos += 1_000_000_000 // one second refills five tokens
    repeat(5) { f.input(p, "who") }
    assertFalse("Slow down." in f.out(p))
    f.input(p, "who")
    assertEquals("Slow down.\n", f.out(p))
  }

  @Test
  fun `more than three drops in a minute disconnects`() {
    val f = Fixture()
    val p = f.player("Ann")
    repeat(20) { f.input(p, "who") }
    repeat(3) { f.input(p, "who") }
    assertNull(f.sink(p).closedReason)
    f.input(p, "who")
    assertEquals("Too much input.", f.sink(p).closedReason)
    assertNull(p.session)
    assertNotNull(p.linkDeadSince)
  }

  @Test
  fun `stop flushes the queue and cancels a cast but keeps combat and recovery`() {
    val f = Fixture(rules = FixedRules(toHit = 0))
    val p = f.player("Ann")
    p.skills[T.BOLT] = 100
    val g = f.mob()
    f.w.startCombat(p, g)
    f.input(p, "cast bolt goblin")
    f.run()
    assertTrue(p.isCasting)
    f.input(p, "north")
    f.input(p, "stop")
    assertContains(f.out(p), "You stop casting.")
    assertFalse(p.isCasting)
    assertTrue(p.actionQueue.isEmpty())
    assertEquals(50, p.mana, "no cost paid")
    assertEquals(0, p.waitState, "no lag applied")
    assertTrue(p.isEngaged)

    p.waitState = 3
    f.input(p, "clear")
    assertEquals(3, p.waitState, "existing recovery is kept")
    assertTrue(p.isEngaged)
  }

  @Test
  fun `mobs issue commands through the same pipeline`() {
    val f = Fixture()
    val p = f.player("Ann")
    val g = f.mob()
    f.w.act(g, "say hello")
    f.w.flushOutput()
    assertContains(f.out(p), "A goblin says, 'hello'")
    f.w.act(g, "north")
    f.run()
    assertEquals(T.YARD, g.room.label)
    assertEquals(Direction.NORTH, f.w.room(T.HALL).exits.keys.first())
  }
}
