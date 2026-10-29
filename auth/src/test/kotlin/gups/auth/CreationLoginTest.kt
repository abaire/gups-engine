package gups.auth

import gups.core.Label
import gups.core.model.CreationChoice
import gups.core.model.CreationOption
import gups.core.model.CreationProvider
import gups.core.model.Player
import gups.core.world.World
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CreationLoginTest {
  private val pw = "correct horse"

  /** Race then class; dwarves can't be mages. */
  private class Heritage : CreationProvider {
    var failAvailable = false
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

    override fun available(
      world: World,
      choice: String,
      answers: Map<String, String>,
    ): List<String> {
      check(!failAvailable) { "broken" }
      val all = choices.first { it.id == choice }.options.map { it.id }
      return if (choice == "class" && answers["race"] == "dwarf") all - "mage" else all
    }

    override fun validate(world: World, answers: Map<String, String>): String? = null

    override fun startRoom(world: World, answers: Map<String, String>): Label? =
      if (answers["class"] == "mage") YARD else null

    override fun created(world: World, player: Player, newCharacter: Boolean) {
      created += player.name to newCharacter
    }
  }

  private fun fixture(): Pair<LoginFixture, Heritage> {
    val f = LoginFixture()
    val h = Heritage()
    f.w.creation = h
    f.account("ann", pw)
    return f to h
  }

  @Test
  fun `a taken name keeps the answers once, and they don't leak into the next character`() {
    val (f, h) = fixture()
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("new")
    assertContains(c.send("Bob"), "Choose your race:")
    c.send("elf")
    c.send("ma")
    f.persistence.saved["Bob"] = null
    val taken = c.send("yes")
    assertContains(taken, "That name is taken.")
    assertContains(taken, "[What will your new character be called? ]")

    val kept = c.send("Cid")
    assertContains(
      kept,
      "Create this character?",
      message = "the kept answers go straight to confirm",
    )
    assertContains(c.send("back"), "Choose your class:", message = "back revisits the last choice")
    c.send("mage")
    assertContains(c.send("cancel"), "Type a name to play")
    assertTrue(f.w.players.isEmpty())

    c.send("new")
    assertContains(c.send("Dan"), "Choose your race:", message = "a fresh character starts over")
    c.send("dwarf")
    assertFalse("Mage" in c.sink.transcript.substringAfterLast("Choose your class:"))
    c.send("warrior")
    assertContains(c.send("y"), "Welcome, Dan.")
    val dan = c.player!!
    assertEquals(mapOf("race" to "dwarf", "class" to "warrior"), dan.choices.toMap())
    assertEquals(HALL, dan.room.label)
    assertEquals(listOf("Dan" to true), h.created)
  }

  @Test
  fun `the provider's start room is used`() {
    val (f, _) = fixture()
    val c = f.connect()
    c.play("ann", pw, "Eve", create = true)
    c.send("elf")
    c.send("mage")
    c.send("yes")
    assertEquals(YARD, c.player!!.room.label)
  }

  @Test
  fun `a failing provider sends the player back to the menu with the message`() {
    val (f, h) = fixture()
    h.failAvailable = true
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    c.send("new")
    val out = c.send("Bob")
    assertContains(out, "Character creation isn't working right now; please tell an instructor.")
    assertContains(out, "Type a name to play")
    assertTrue(f.w.diagnostics.any { it.kind == "plugin" && "broken" in it.message })
  }

  @Test
  fun `a saved character with missing choices is asked them, then enters`() {
    val (f, h) = fixture()
    f.persistence.saved["Fay"] = f.store.byLogin("ann")!!.id
    val c = f.connect()
    c.send("ann")
    c.send(pw)
    val asked = c.send("fay")
    assertContains(asked, "Fay has choices to make before playing.")
    assertContains(asked, "Choose your race:")
    c.send("1")
    c.send("2")
    assertContains(c.send("yes"), "Welcome back, Fay.")
    assertEquals(mapOf("race" to "elf", "class" to "mage"), c.player!!.choices.toMap())
    assertEquals(listOf("Fay" to false), h.created)
  }
}
