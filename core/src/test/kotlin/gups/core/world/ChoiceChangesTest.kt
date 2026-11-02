package gups.core.world

import gups.core.Label
import gups.core.fixtures.FixtureRules
import gups.core.model.CreationChoice
import gups.core.model.CreationOption
import gups.core.model.CreationProvider
import gups.core.model.ItemLocation
import gups.core.model.Player
import gups.core.model.Restriction
import gups.core.model.RestrictionPoint
import gups.core.model.Trait
import gups.core.rules.RulesContext
import gups.core.rules.StartingStats
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChoiceChangesTest {
  /** Race then class; dwarves can't be mages, and elves won't be warriors. */
  private class Heritage : CreationProvider {
    override val choices =
      listOf(
        CreationChoice(
          "race",
          "Race",
          options =
            listOf(
              CreationOption("elf", "Elf", "", traits = listOf("elf")),
              CreationOption("dwarf", "Dwarf", "", traits = listOf("dwarf")),
            ),
        ),
        CreationChoice(
          "class",
          "Class",
          options =
            listOf(
              CreationOption("warrior", "Warrior", "", traits = listOf("warrior")),
              CreationOption("mage", "Mage", "", traits = listOf("mage")),
            ),
        ),
      )

    override fun available(world: World, choice: String, answers: Map<String, String>) =
      choices.first { it.id == choice }.options.map { it.id } -
        if (choice == "class" && answers["race"] == "dwarf") setOf("mage") else emptySet()

    override fun validate(world: World, answers: Map<String, String>): String? =
      if (answers["race"] == "elf" && answers["class"] == "warrior") "Elves won't fight." else null

    override fun startRoom(world: World, answers: Map<String, String>): Label? = null

    override fun created(world: World, player: Player, newCharacter: Boolean) = Unit
  }

  private class CountingRules : FixtureRules() {
    var startingStats = 0

    override fun startingStats(c: RulesContext): StartingStats {
      startingStats++
      return super.startingStats(c)
    }
  }

  private class Captures : Persistence {
    val immediate = mutableListOf<String>()

    override fun checkpoint(world: World, cause: String, immediate: Boolean) {
      if (immediate) this.immediate += cause
    }
  }

  private val rules = CountingRules()
  private val f = Fixture(rules = rules)
  private val hp = f.fs.health
  private val captures = Captures()

  init {
    f.w.creation = Heritage()
    f.w.persistence = captures
    f.w.traits =
      mapOf(
        "elf" to Trait("elf"),
        "dwarf" to Trait("dwarf", maxVitalDeltas = mapOf(hp to 20)),
        "warrior" to Trait("warrior"),
        "mage" to Trait("mage"),
      )
  }

  private fun dwarf(): Player =
    f.w.spawnPlayer("Ann", T.HALL, choices = mapOf("race" to "dwarf", "class" to "warrior")).also {
      f.connect(it)
    }

  @Test
  fun `setChoices refuses an unavailable option or a validation refusal with no effect`() {
    val p = dwarf()
    val before = p.choices.toMap()
    assertEquals(
      "Option 'mage' is not available for choice 'class'",
      f.w.setChoices(p, mapOf("class" to "mage")),
    )
    assertEquals("Elves won't fight.", f.w.setChoices(p, mapOf("race" to "elf")))
    assertEquals(before, p.choices.toMap())
    assertEquals(listOf("dwarf", "warrior"), p.traits.map { it.name })
  }

  @Test
  fun `setChoices applies several changes as one`() {
    val p = dwarf()
    assertNull(f.w.setChoices(p, mapOf("race" to "elf", "class" to "mage")))
    assertEquals(mapOf("race" to "elf", "class" to "mage"), p.choices.toMap())
    assertEquals(listOf("elf", "mage"), p.traits.map { it.name })
  }

  @Test
  fun `setChoices throws for an unknown choice or option`() {
    val p = dwarf()
    assertFailsWith<IllegalArgumentException> { f.w.setChoices(p, mapOf("deity" to "sun")) }
    assertFailsWith<IllegalArgumentException> { f.w.setChoices(p, mapOf("race" to "orc")) }
  }

  @Test
  fun `a change recomputes traits, caps vitals, doesn't rerun starting_stats, and captures at once`() {
    val p = dwarf()
    val max = p.max(hp)
    assertEquals(p.baseMax(hp) + 20, max)
    f.setVital(p, hp, max)
    val runs = rules.startingStats
    f.run()
    captures.immediate.clear()

    assertNull(f.w.setChoices(p, mapOf("race" to "elf", "class" to "mage")))
    assertEquals(max - 20, p.max(hp))
    assertEquals(max - 20, p.current(hp))
    assertEquals(runs, rules.startingStats)
    f.run()
    assertEquals(listOf("choices"), captures.immediate)
    assertTrue(f.w.invariantViolations().isEmpty())
  }

  private fun refuseSwordsToMages() {
    f.w.restrictions =
      mapOf(
        RestrictionPoint.WEAR to
          listOf(
            Restriction { _, r ->
              if (r.actor.hasTrait("mage") && r.item?.protoLabel == T.SWORD)
                "Mages can't use swords."
              else null
            }
          )
      )
  }

  @Test
  fun `a change makes worn items the new answers refuse inactive, with the message`() {
    val p = dwarf()
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    refuseSwordsToMages()
    f.out(p)
    assertNull(f.w.setChoices(p, mapOf("race" to "elf", "class" to "mage")))
    assertEquals(ItemLocation.Equipped(p.id, "wield"), sword.location)
    assertEquals(setOf("wield"), p.inactiveSlots)
    f.run()
    val out = f.out(p)
    assertContains(out, "Mages can't use swords.")
    assertFalse(out.contains("no longer fits"))
    assertTrue(f.w.invariantViolations().isEmpty())
  }

  @Test
  fun `a wear refusal holds until a later change allows the item`() {
    val p = dwarf()
    f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    refuseSwordsToMages()
    assertNull(f.w.setChoices(p, mapOf("race" to "elf", "class" to "mage")))
    assertEquals(setOf("wield"), p.inactiveSlots)

    // An unrelated refresh doesn't ask the restriction again.
    f.w.spawnItem(T.SWORD, ItemLocation.Carried(p.id))
    assertEquals(setOf("wield"), p.inactiveSlots)

    f.out(p)
    assertNull(f.w.setChoices(p, mapOf("race" to "dwarf", "class" to "warrior")))
    assertTrue(p.inactiveSlots.isEmpty())
    f.run()
    assertContains(f.out(p), "fits you again")
  }

  @Test
  fun `taking off a refused item and wearing another clears the refusal for that slot`() {
    val p = dwarf()
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    refuseSwordsToMages()
    assertNull(f.w.setChoices(p, mapOf("race" to "elf", "class" to "mage")))
    f.w.moveItem(sword, ItemLocation.Carried(p.id))
    assertTrue(p.inactiveSlots.isEmpty())
    f.w.restrictions = emptyMap()
    f.w.moveItem(sword, ItemLocation.Equipped(p.id, "wield"))
    assertTrue(p.inactiveSlots.isEmpty())
  }

  @Test
  fun `granting a mob a trait the wear restriction reads makes its refused gear inactive`() {
    val m = f.mob()
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Equipped(m.id, "wield"))
    refuseSwordsToMages()
    assertNull(f.w.grantTrait(m, "mage"))
    assertEquals(ItemLocation.Equipped(m.id, "wield"), sword.location)
    assertEquals(setOf("wield"), m.inactiveSlots)
    assertNull(f.w.removeTrait(m, "mage"))
    assertTrue(m.inactiveSlots.isEmpty())
  }

  @Test
  fun `a reload asks the wear restriction again`() {
    val p = dwarf()
    f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    f.w.restrictions =
      mapOf(RestrictionPoint.WEAR to listOf(Restriction { _, _ -> "Swords are banned." }))
    assertTrue(p.inactiveSlots.isEmpty())
    f.out(p)
    f.w.applyPrototypes(f.w.protos)
    assertEquals(setOf("wield"), p.inactiveSlots)
    f.run()
    assertContains(f.out(p), "Swords are banned.")
  }

  @Test
  fun `a cursed item the wear restriction refuses stays worn`() {
    val p = dwarf()
    val sword = f.w.spawnItem(T.SWORD, ItemLocation.Equipped(p.id, "wield"))
    refuseSwordsToMages()
    f.w.restrictions =
      f.w.restrictions + (RestrictionPoint.REMOVE to listOf(Restriction { _, _ -> "It clings." }))
    assertNull(f.w.setChoices(p, mapOf("race" to "elf", "class" to "mage")))
    assertEquals(ItemLocation.Equipped(p.id, "wield"), sword.location)
    assertEquals(setOf("wield"), p.inactiveSlots)
  }

  @Test
  fun `removing an answer makes the choice missing`() {
    val p = dwarf()
    assertFalse(f.w.hasMissingChoices(p))
    assertNull(f.w.setChoices(p, mapOf("class" to null)))
    assertEquals(mapOf("race" to "dwarf"), p.choices.toMap())
    assertEquals(listOf("dwarf"), p.traits.map { it.name })
    assertTrue(f.w.hasMissingChoices(p))
  }

  @Test
  fun `an option's trait can't be removed or granted twice through the trait verbs`() {
    val p = dwarf()
    assertEquals("Trait 'dwarf' was not granted directly", f.w.removeTrait(p, "dwarf"))
    assertTrue(p.hasTrait("dwarf"))
    assertNull(f.w.grantTrait(p, "mage"))
    assertEquals("Trait 'mage' already granted", f.w.grantTrait(p, "mage"))
    assertEquals(listOf("dwarf", "mage", "warrior"), p.traits.map { it.name })
  }

  @Test
  fun `granting a trait to a player checks worn items and captures at once`() {
    val p = dwarf()
    f.run()
    captures.immediate.clear()
    f.w.grantTrait(p, "mage")
    f.run()
    assertEquals(listOf("choices"), captures.immediate)
  }
}
