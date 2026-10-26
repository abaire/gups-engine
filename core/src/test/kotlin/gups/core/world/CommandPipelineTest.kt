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
    assertEquals("look", reg.resolve(p, "loo")?.name)
    assertEquals(Lane.TICK, reg.resolve(p, "north")?.lane)
    assertEquals(Lane.TICK, reg.resolve(p, "ne")?.lane) // a standard direction even with no exit
    assertEquals(Lane.FAST, reg.resolve(p, "say")?.lane)
    assertNull(reg.resolve(p, "xyzzy"))
    f.input(p, "xyzzy")
    assertEquals("Huh?\n", f.out(p))
  }

  @Test
  fun `a prefix matching more than one command name is ambiguous`() {
    val f = Fixture()
    val p = f.player("Ann")
    val reg = f.w.commands
    assertNull(reg.resolve(p, "cl")) // clear, close
    assertNull(reg.resolve(p, "g")) // gossip, get, give
    assertNull(reg.resolve(p, "lo")) // look, lock
    assertEquals("close", reg.resolve(p, "clo")?.name)
    reg.registerGlobal(FastCommand("quests") {})
    reg.registerPlugin(FastCommand("questlog") {})
    assertNull(reg.resolve(p, "quest"), "ambiguity spans global and plugin commands")
    assertEquals("questlog", reg.resolve(p, "questl")?.name)
    f.input(p, "cl")
    assertEquals("Huh?\n", f.out(p))
  }

  @Test
  fun `a world without aliases has no shorthands for engine commands`() {
    val f = Fixture(aliases = CommandAliases.NONE)
    val p = f.player("Ann")
    val reg = f.w.commands
    assertNull(reg.resolve(p, "attack"))
    assertNull(reg.resolve(p, "take"))
    assertEquals("kill", reg.resolve(p, "k")?.name) // only as a unique prefix
    f.input(p, "'hello")
    assertEquals("Huh?\n", f.out(p))
  }

  @Test
  fun `the world's aliases and prefixes resolve to their commands`() {
    val aliases =
      CommandAliases(
        words = mapOf("peer" to "look", "gaze" to "look"),
        prefixes = mapOf('!' to "say", ';' to "say", '%' to "cast"),
      )
    val f = Fixture(aliases = aliases)
    val p = f.player("Ann")
    val reg = f.w.commands
    assertEquals("look", reg.resolve(p, "peer")?.name)
    assertEquals("look", reg.resolve(p, "gaze")?.name)
    assertNull(reg.resolve(p, "attack")) // not one of this world's aliases
    assertEquals("say" to "hi there", parseLine("!hi there", aliases.prefixes))
    assertEquals("say" to "hi", parseLine(";  hi", aliases.prefixes))
    assertEquals("cast" to "spark", parseLine("%spark", aliases.prefixes))
    assertEquals("'hi" to "", parseLine("'hi", aliases.prefixes))
    f.input(p, "!hello")
    assertContains(f.out(p), "hello")
  }

  @Test
  fun `alias problems name bad aliases and unknown commands`() {
    val problems =
      CommandAliases(
          words = mapOf("look" to "look", "n" to "look", "zz" to "dance", "Ok" to "look"),
          prefixes = mapOf('a' to "say", '#' to "dance"),
        )
        .problems()
    assertEquals(
      listOf(
        "alias 'Ok' must match [a-z][a-z0-9_]*",
        "alias 'look' is already an engine command",
        "alias 'n' is a direction",
        "alias 'zz' names 'dance', which is not an engine command",
        "prefix '#' names 'dance', which is not an engine command",
        "prefix 'a' must not be a letter, digit, space or @",
      ),
      problems,
    )
    assertEquals(emptyList(), TEMPLATE_ALIASES.problems())
  }

  @Test
  fun `prefixes match aliases, and an exact alias is never ambiguous`() {
    val f = Fixture()
    val p = f.player("Ann")
    val reg = f.w.commands
    assertEquals("kill", reg.resolve(p, "att")?.name) // the attack alias
    assertEquals("get", reg.resolve(p, "ta")?.name) // the take alias
    assertEquals("inventory", reg.resolve(p, "in")?.name) // name and alias of one command
    assertEquals("look", reg.resolve(p, "l")?.name) // exact alias, though lock also starts with l
    reg.registerPlugin(FastCommand("tackle") {})
    assertNull(reg.resolve(p, "ta"), "take and tackle")
  }

  @Test
  fun `item commands come from equipment, then inventory, then the ground, not containers`() {
    val ran = mutableListOf<String>()
    fun rubbable(label: String, wear: String? = null) =
      gups.core.model.ItemProto(
        gups.core.Label("//t:$label"),
        "a $label",
        listOf(label),
        wear = wear,
        commands = mapOf("rub" to ZoneCommand { _, _, _, _ -> ran += label }),
      )
    val protos = listOf(rubbable("stone"), rubbable("lamp"), rubbable("ring", wear = "finger"))
    val f =
      Fixture(protos = gups.core.model.Prototypes(T.rooms, T.mobs, T.items + protos, T.skills))
    val p = f.player("Ann")
    val hall = gups.core.model.ItemLocation.InRoom(f.w.room(T.HALL).id, 0)
    // Spawned so that the ground item has the lowest ID and the worn one the highest.
    val stone = f.w.spawnItem(protos[0].label, hall)
    val lamp = f.w.spawnItem(protos[1].label, gups.core.model.ItemLocation.Carried(p.id))
    val ring = f.w.spawnItem(protos[2].label, gups.core.model.ItemLocation.Equipped(p.id, "finger"))
    fun rub(): String? {
      f.input(p, "rub")
      f.run(3)
      f.out(p)
      return ran.removeLastOrNull()
    }
    assertEquals("ring", rub())
    f.w.destroy(ring)
    assertEquals("lamp", rub())
    val bag = f.w.spawnItem(T.BAG, gups.core.model.ItemLocation.Carried(p.id))
    f.w.moveItem(lamp, gups.core.model.ItemLocation.Contained(bag.id))
    assertEquals("stone", rub())
    f.w.moveItem(stone, gups.core.model.ItemLocation.Contained(bag.id))
    assertNull(f.w.commands.resolve(p, "rub"), "items in a container grant no commands")
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
  fun `every input line counts against a raw line limit`() {
    val f = Fixture()
    val p = f.player("Ann")
    // Unknown verbs and tick-lane commands skip the fast-lane bucket but not the line limit.
    repeat(15) { f.input(p, "xyzzy") }
    repeat(10) { f.input(p, "advance") }
    f.out(p)
    f.input(p, "xyzzy")
    assertEquals("", f.out(p), "lines over the limit are dropped silently")
    repeat(2) { f.input(p, "xyzzy") }
    assertNull(f.sink(p).closedReason)
    f.input(p, "xyzzy")
    assertEquals("Too much input.", f.sink(p).closedReason)
    assertNull(p.session)
  }

  @Test
  fun `the raw line limit refills at twenty-five lines a second`() {
    val f = Fixture()
    val p = f.player("Ann")
    repeat(25) { f.input(p, "xyzzy") }
    f.out(p)
    f.nanos += 200_000_000 // a fifth of a second refills five lines
    repeat(5) { f.input(p, "xyzzy") }
    assertEquals("Huh?\n".repeat(5), f.out(p))
    f.input(p, "xyzzy")
    assertEquals("", f.out(p))
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
