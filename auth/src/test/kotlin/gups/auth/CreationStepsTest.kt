package gups.auth

import gups.core.ClientInputEvent
import gups.core.Label
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.CreationAttempt
import gups.core.model.CreationChoice
import gups.core.model.CreationOption
import gups.core.model.CreationProvider
import gups.core.model.CreationStep
import gups.core.model.Player
import gups.core.model.StepReply
import gups.core.model.StepView
import gups.core.world.Persistence
import gups.core.world.SavedCharacter
import gups.core.world.World
import gups.core.world.handle
import gups.core.world.quit
import gups.core.world.setBaseStat
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreationStepsTest {
  private val pw = "correct horse"

  private class RecordingStep(
    override val id: String = "roll",
    override val title: String = "Roll",
  ) : CreationStep {
    val seeds = mutableListOf<Long>()
    val counts = mutableListOf<Int>()

    override fun applies(world: World, attempt: CreationAttempt) = true

    override fun show(world: World, attempt: CreationAttempt): StepView {
      seeds += attempt.seed
      counts += attempt.count
      return StepView("Roll: 18. Type ok:")
    }

    override fun input(world: World, attempt: CreationAttempt, line: String): StepReply {
      return if (line.trim().lowercase() == "ok") {
        attempt.answers[id] = "18"
        StepReply.Done
      } else {
        StepReply.Stay("Type ok:")
      }
    }

    override fun summary(world: World, attempt: CreationAttempt): String? =
      attempt.answers[id]?.let { "Roll $it" }
  }

  private class StepHeritage(val step: RecordingStep) : CreationProvider {
    val created = mutableListOf<Pair<String, Boolean>>()

    override val choices =
      listOf(
        CreationChoice(
          "race",
          "Race",
          options =
            listOf(
              CreationOption("elf", "Elf", "Quick."),
              CreationOption("dwarf", "Dwarf", "Hardy."),
            ),
        )
      )

    override val steps = listOf(step)

    override fun available(world: World, choice: String, answers: Map<String, String>) =
      choices.first { it.id == choice }.options.map { it.id }

    override fun validate(world: World, answers: Map<String, String>): String? = null

    override fun startRoom(world: World, answers: Map<String, String>): Label? = null

    override fun created(world: World, player: Player, newCharacter: Boolean) {
      created += player.name to newCharacter
    }
  }

  private class Heritage : CreationProvider {
    val created = mutableListOf<Pair<String, Boolean>>()

    override val choices =
      listOf(
        CreationChoice(
          "race",
          "Race",
          options =
            listOf(
              CreationOption("elf", "Elf", "Quick."),
              CreationOption("dwarf", "Dwarf", "Hardy."),
            ),
        ),
        CreationChoice(
          "class",
          "Class",
          options =
            listOf(
              CreationOption("warrior", "Warrior", "Strong."),
              CreationOption("mage", "Mage", "Clever."),
            ),
        ),
      )

    override fun available(world: World, choice: String, answers: Map<String, String>) =
      choices.first { it.id == choice }.options.map { it.id }

    override fun validate(world: World, answers: Map<String, String>): String? = null

    override fun startRoom(world: World, answers: Map<String, String>): Label? = null

    override fun created(world: World, player: Player, newCharacter: Boolean) {
      created += player.name to newCharacter
    }
  }

  /** Saved characters with their answers, restored by name. */
  private class SavedChoices : Persistence {
    val saved = LinkedHashMap<String, Pair<Long?, Map<String, String>>>()
    val savedStats = LinkedHashMap<String, Map<String, Int>>()

    override fun checkpoint(world: World, cause: String, immediate: Boolean) {
      for (p in world.players) {
        saved[p.name] = p.accountId to p.choices.toMap()
        savedStats[p.name] = p.baseStats.toMap()
      }
    }

    override fun characters(accountId: Long) =
      saved.filterValues { it.first == accountId }.keys.toList()

    override fun savedCharacter(name: String) =
      saved.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.let { SavedCharacter(it.key, it.value.first) }

    override fun loadCharacter(world: World, name: String): Player? {
      val c = savedCharacter(name) ?: return null
      val p =
        world.spawnPlayer(
          c.name,
          HALL,
          accountId = c.accountId,
          choices = saved.getValue(c.name).second,
        )
      savedStats[c.name]?.let { stats ->
        for ((k, v) in stats) {
          world.setBaseStat(p, k, v)
        }
      }
      return p
    }
  }

  /** Records text, prompts and Char.Creation messages, as a web client asking for `Char` would. */
  private class WebSink : OutputSink {
    val all = StringBuilder()
    val creation = mutableListOf<Any?>()

    override fun send(text: String) {
      all.append(text)
    }

    override fun prompt(text: String) {
      all.append("[$text]")
    }

    override fun gmcp(pkg: String, data: Any?) {
      if (pkg == "Char.Creation") creation += data
    }

    override fun wantsGmcp(pkg: String) = pkg.startsWith("Char")

    override fun close(reason: String) = Unit
  }

  private val f = LoginFixture()
  private val h = Heritage().also { f.w.creation = it }
  private val saved = SavedChoices().also { f.w.persistence = it }
  private val ann = f.account("ann", pw)

  private val namePrompt = "[What will your new character be called? ]"
  private val menu = "Type a name to play"

  private fun creating(name: String): LoginFixture.Client =
    f.connect().also {
      it.send("ann")
      it.send(pw)
      it.send("new")
      assertContains(it.send(name), "Choose your race:")
    }

  @Test
  fun `back from the first choice returns to the name prompt, keeping the answers`() {
    val c = creating("Bob")
    assertEquals(namePrompt, c.send("back"))
    assertTrue(f.w.players.isEmpty())

    assertContains(c.send("Bob"), "Choose your race:")
    c.send("elf")
    c.send("mage")
    saved.saved["Bob"] = null to emptyMap()
    c.send("yes")
    assertContains(c.send("Cid"), "Create this character?")
    assertContains(c.send("back"), "Choose your class:")
    assertEquals(namePrompt, c.send("back"), "class is now the first choice asked")
    assertContains(c.send("Dee"), "Choose your class:", message = "the race answer was kept")
    c.send("warrior")
    assertContains(c.send("yes"), "Welcome, Dee.")
    assertEquals(mapOf("race" to "elf", "class" to "warrior"), c.player!!.choices.toMap())
  }

  @Test
  fun `back from the second choice and from the confirmation goes one step back`() {
    val c = creating("Bob")
    c.send("elf")
    assertContains(c.send("back"), "Choose your race:")
    c.send("dwarf")
    c.send("warrior")
    assertContains(c.send("back"), "Choose your class:")
    c.send("mage")
    assertContains(c.send("yes"), "Welcome, Bob.")
    assertEquals(mapOf("race" to "dwarf", "class" to "mage"), c.player!!.choices.toMap())
  }

  @Test
  fun `cancel at every step returns to the menu and creates nothing`() {
    for (answers in listOf(listOf(), listOf("elf"), listOf("elf", "mage"))) {
      val c = creating("Bob")
      answers.forEach { c.send(it) }
      assertContains(c.send("cancel"), menu, message = "cancel after $answers")
      assertTrue(f.w.players.isEmpty())
      assertNull(saved.savedCharacter("Bob"))
      c.send("quit")
    }
    assertTrue(h.created.isEmpty())
  }

  @Test
  fun `the idle timeout closes the connection in the middle of the steps`() {
    val c = creating("Bob")
    f.w.runPulses(200)
    c.send("elf")
    f.w.runPulses(239)
    assertNull(c.sink.closed, "typing an answer restarts the idle clock")
    f.w.runPulses(1)
    assertEquals("You took too long. Goodbye.", c.sink.closed)
    assertTrue(f.w.players.isEmpty())
  }

  @Test
  fun `a saved character whose option was removed is asked only that choice`() {
    saved.saved["Fay"] = ann.id to mapOf("race" to "elf", "class" to "necromancer")
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    val asked = c.send("fay")
    assertContains(asked, "Fay has choices to make before playing.")
    assertContains(asked, "Choose your class:")
    assertFalse("Choose your race:" in asked)
    assertContains(c.send("warrior"), "Fay: Race Elf, Class Warrior. Create")
    assertContains(c.send("yes"), "Welcome back, Fay.")
    assertEquals(mapOf("race" to "elf", "class" to "warrior"), c.player!!.choices.toMap())
    assertEquals(listOf("Fay" to false), h.created)
  }

  @Test
  fun `a character saved before the plugin is asked every choice, and cancel doesn't enter`() {
    saved.saved["Gus"] = ann.id to emptyMap()
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    assertContains(c.send("gus"), "Choose your race:")
    c.send("dwarf")
    assertContains(c.send("cancel"), menu)
    assertNull(c.player)
    assertTrue(f.w.players.none { it.session != null })
    assertTrue(h.created.isEmpty())
  }

  @Test
  fun `cancelling a saved character's missing choices leaves it out of the world`() {
    saved.saved["Gus"] = ann.id to emptyMap()
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("gus")
    c.send("cancel")
    assertTrue(f.w.players.isEmpty())
    assertContains(c.send("gus"), "Choose your race:", message = "asked again, not taken over")
  }

  @Test
  fun `back from the first missing choice of a saved character returns to the menu`() {
    saved.saved["Gus"] = ann.id to emptyMap()
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("gus")
    assertContains(c.send("back"), menu)
    assertNull(c.player)
  }

  @Test
  fun `a character taken over in the world isn't asked its missing choices`() {
    val gus = f.w.spawnPlayer("Gus", HALL, accountId = ann.id)
    saved.saved["Gus"] = ann.id to emptyMap()
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    val out = c.send("gus")
    assertFalse("choices to make" in out, out)
    assertEquals(gus, c.player)
  }

  @Test
  fun `a web session goes from its token to the same steps and gets Char Creation`() {
    val sink = WebSink()
    val id = SessionId(99)
    f.w.handle(WorldControlEvent.Connect(id, sink, "10.0.0.1", "ws", ann.id))
    f.settle()
    assertContains(sink.all, menu)
    for (line in listOf("new", "Wes", "elf", "mage", "yes")) {
      f.w.handle(ClientInputEvent(id, line, 0))
      f.settle()
    }
    assertContains(sink.all, "Choose your race:")
    assertContains(sink.all, "Welcome, Wes.")
    val wes = f.w.players.single { it.session == id }
    assertEquals(mapOf("race" to "elf", "class" to "mage"), wes.choices.toMap())
    assertEquals(4, sink.creation.size, "${sink.creation}")
    assertNull(sink.creation.last())
    assertEquals(
      listOf("choice", "choice", "confirm"),
      sink.creation.dropLast(1).map { (it as Map<*, *>)["step"] },
    )
  }

  @Test
  fun `an end-to-end telnet-style run through the step reaches the confirmation and creates the character`() {
    val step = RecordingStep()
    val sh = StepHeritage(step)
    f.w.creation = sh
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("new")
    assertContains(c.send("Zoe"), "Choose your race:")
    assertContains(c.send("elf"), "Roll: 18. Type ok:")
    assertContains(c.send("ok"), "Zoe: Race Elf, Roll Roll 18. Create this character?")
    assertContains(c.send("yes"), "Welcome, Zoe.")
    assertEquals(1, f.w.players.size)
    assertEquals("Zoe", c.player!!.name)
    assertEquals(mapOf("race" to "elf", "roll" to "18"), c.player!!.choices.toMap())
    assertEquals(listOf("Zoe" to true), sh.created)
  }

  @Test
  fun `cancel and then new gives the same attempt seed`() {
    val step = RecordingStep()
    f.w.creation = StepHeritage(step)
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("new")
    c.send("Zoe")
    c.send("elf")
    c.send("cancel")
    c.send("new")
    c.send("Zoe")
    c.send("elf")
    assertEquals(2, step.seeds.size)
    assertEquals(step.seeds[0], step.seeds[1])
  }

  @Test
  fun `a second character on the same account gets a different seed and creations counter increments`() {
    val step = RecordingStep()
    f.w.creation = StepHeritage(step)
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("new")
    c.send("Zoe")
    c.send("elf")
    c.send("ok")
    c.send("yes")
    f.settle()
    assertEquals(1, f.store.byId(ann.id)!!.creations)
    f.w.quit(c.player!!)

    val c2 = f.connect()
    c2.send("ann")
    c2.send(pw)
    c2.send("new")
    c2.send("Max")
    c2.send("elf")
    c2.send("ok")
    c2.send("yes")
    f.settle()
    assertEquals(2, f.store.byId(ann.id)!!.creations)
    assertEquals(2, step.seeds.size)
    assertNotEquals(step.seeds[0], step.seeds[1], "seeds must differ")
  }

  @Test
  fun `with an account whose stored creations is 3, the attempt count is 3`() {
    val ben = f.account("ben", pw)
    for (i in 0 until 3) f.store.countCreation(ben.id)
    assertEquals(3, f.store.byId(ben.id)!!.creations)

    val step = RecordingStep()
    f.w.creation = StepHeritage(step)
    val c = f.connect()
    c.send("ben")
    c.send(pw)
    c.send("new")
    c.send("Leo")
    c.send("elf")
    assertEquals(listOf(3), step.counts)
  }

  @Test
  fun `a name-taken retry keeps finished steps`() {
    val step = RecordingStep()
    f.w.creation = StepHeritage(step)
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("new")
    c.send("Bob")
    c.send("elf")
    c.send("ok")
    saved.saved["Bob"] = null to emptyMap()
    val reply = c.send("yes")
    assertContains(reply, "That name is taken.")
    assertEquals(namePrompt, reply.lines().last())
    val confirm = c.send("Cid")
    assertContains(confirm, "Create this character?")
    assertFalse("Roll: 18" in confirm)
    assertFalse("Choose your race:" in confirm)
    val welcomed = c.send("yes")
    assertContains(welcomed, "Welcome, Cid.")
    assertEquals(mapOf("race" to "elf", "roll" to "18"), c.player!!.choices.toMap())
  }

  private class StatAllocationStep(
    override val id: String = "stats",
    override val title: String = "Stats",
  ) : CreationStep {
    override fun applies(world: World, attempt: CreationAttempt) = true

    override fun show(world: World, attempt: CreationAttempt): StepView =
      StepView("Spend points. Type bonus:")

    override fun input(world: World, attempt: CreationAttempt, line: String): StepReply {
      val bonus = line.trim().toIntOrNull() ?: return StepReply.Stay("Enter a number:")
      attempt.state["bonus_str"] = bonus
      attempt.answers[id] = "$bonus bonus"
      return StepReply.Done
    }

    override fun summary(world: World, attempt: CreationAttempt): String? = attempt.answers[id]
  }

  private class StatHeritage(val step: StatAllocationStep) : CreationProvider {
    override val choices = emptyList<CreationChoice>()
    override val steps = listOf(step)

    override fun available(world: World, choice: String, answers: Map<String, String>) =
      emptyList<String>()

    override fun validate(world: World, answers: Map<String, String>): String? = null

    override fun startRoom(world: World, answers: Map<String, String>): Label? = null

    override fun created(world: World, player: Player, newCharacter: Boolean) = Unit

    override fun prepare(world: World, player: Player, attempt: CreationAttempt) {
      val bonus = (attempt.state["bonus_str"] as? Int) ?: 0
      world.setBaseStat(player, "str", player.baseStat("str") + bonus)
    }
  }

  @Test
  fun `account creation with step whose prepare applies values from state ends with base stats saved`() {
    val step = StatAllocationStep()
    f.w.creation = StatHeritage(step)
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("new")
    val stepPrompt = c.send("Gimli")
    assertContains(stepPrompt, "Spend points. Type bonus:")
    val confirm = c.send("5")
    assertContains(confirm, "Create this character?")
    val welcomed = c.send("yes")
    assertContains(welcomed, "Welcome, Gimli.")
    val player = c.player!!
    assertEquals(15, player.baseStat("str"))

    f.w.checkpointNow("test")
    f.w.quit(player)

    val c2 = f.connect()
    c2.send("ann")
    c2.send(pw)
    c2.send("Gimli")
    val reloaded = c2.player!!
    assertEquals(15, reloaded.baseStat("str"))
  }
}
