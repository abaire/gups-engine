package gups.auth

import gups.core.ClientInputEvent
import gups.core.Label
import gups.core.OutputSink
import gups.core.SessionId
import gups.core.WorldControlEvent
import gups.core.model.CreationChoice
import gups.core.model.CreationOption
import gups.core.model.CreationProvider
import gups.core.model.Player
import gups.core.world.Persistence
import gups.core.world.SavedCharacter
import gups.core.world.World
import gups.core.world.handle
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreationStepsTest {
  private val pw = "correct horse"

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

    override fun checkpoint(world: World, cause: String, immediate: Boolean) {
      for (p in world.players) saved[p.name] = p.accountId to p.choices.toMap()
    }

    override fun characters(accountId: Long) =
      saved.filterValues { it.first == accountId }.keys.toList()

    override fun savedCharacter(name: String) =
      saved.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.let { SavedCharacter(it.key, it.value.first) }

    override fun loadCharacter(world: World, name: String): Player? {
      val c = savedCharacter(name) ?: return null
      return world.spawnPlayer(
        c.name,
        HALL,
        accountId = c.accountId,
        choices = saved.getValue(c.name).second,
      )
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
}
