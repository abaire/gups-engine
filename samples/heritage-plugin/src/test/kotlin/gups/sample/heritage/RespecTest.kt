package gups.sample.heritage

import gups.pluginapi.PluginChoice
import gups.pluginapi.PluginOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RespecTest {
  private val world = FakeWorld()
  private val creation = HeritageCreation()
  private val respec = Respec(creation)

  private fun actor(race: String, cls: String, skills: Map<String, Int> = emptyMap()) =
    FakeActor(choices = mapOf("race" to race, "class" to cls), skills = skills)

  /** A creation whose class choice also offers an option the real availability never lists. */
  private class WideCreation : HeritageCreation() {
    override val choices =
      listOf(
        PluginChoice("race", "Race", HeritagePlugin.RACE_OPTIONS),
        PluginChoice(
          "class",
          "Class",
          HeritagePlugin.CLASS_OPTIONS +
            PluginOption("elementalist", "Elementalist", "Shapers of the elements."),
        ),
      )
  }

  // ---- refusal ------------------------------------------------------------------------------

  @Test
  fun `the current option is refused unless allowSame`() {
    val a = actor("human", "warrior")
    assertEquals(
      "That is already your class.",
      respec.refusal(RespecRequest(world, a, false), "class", "warrior"),
    )
    assertNull(respec.refusal(RespecRequest(world, a, true), "class", "warrior"))
  }

  @Test
  fun `an option the availability rules don't list is refused`() {
    assertEquals(
      "Mage isn't open to you.",
      respec.refusal(RespecRequest(world, actor("dwarf", "warrior"), false), "class", "mage"),
    )
  }

  @Test
  fun `a change that strands a later choice is refused`() {
    assertEquals(
      "That would leave your class (Mage) unavailable.",
      respec.refusal(RespecRequest(world, actor("elf", "mage"), false), "race", "dwarf"),
    )
  }

  @Test
  fun `an open option is allowed`() {
    assertNull(
      respec.refusal(RespecRequest(world, actor("human", "warrior"), false), "class", "mage")
    )
  }

  @Test
  fun `validate has the last word`() {
    val strict =
      object : HeritageCreation() {
        override fun validate(
          world: gups.pluginapi.PluginWorld,
          answers: Map<String, String>,
        ): String? = if (answers["class"] == "cleric") "No clerics today." else null
      }
    assertEquals(
      "No clerics today.",
      Respec(strict)
        .refusal(RespecRequest(world, actor("human", "warrior"), false), "class", "cleric"),
    )
  }

  @Test
  fun `unknown choices and options are errors`() {
    val a = actor("human", "warrior")
    assertEquals(
      "unknown choice 'deity'",
      assertFailsWith<IllegalArgumentException> {
          respec.refusal(RespecRequest(world, a, false), "deity", "x")
        }
        .message,
    )
    assertEquals(
      "unknown option 'bard' for class",
      assertFailsWith<IllegalArgumentException> {
          respec.refusal(RespecRequest(world, a, false), "class", "bard")
        }
        .message,
    )
  }

  // ---- menu ---------------------------------------------------------------------------------

  @Test
  fun `the menu lists every option in order with its refusal`() {
    val menu = respec.menu(RespecRequest(world, actor("dwarf", "warrior"), false), listOf("class"))
    assertEquals(1, menu.size)
    val c = menu.single()
    assertEquals("class", c.choice)
    assertEquals("Class", c.title)
    assertEquals("warrior", c.current)
    assertEquals(listOf("warrior", "mage", "cleric"), c.options.map { it.id })
    assertEquals(
      listOf("That is already your class.", "Mage isn't open to you.", null),
      c.options.map { it.refusal },
    )
    assertEquals("Mage", c.options[1].label)
    assertEquals("Scholars of arcane destruction.", c.options[1].summary)
  }

  @Test
  fun `the menu follows the order of the choices asked for`() {
    val menu =
      respec.menu(RespecRequest(world, actor("human", "warrior"), true), listOf("class", "race"))
    assertEquals(listOf("class", "race"), menu.map { it.choice })
    assertEquals(null, menu[0].options[0].refusal) // allowSame
  }

  @Test
  fun `toPlain uses the builtin's keys`() {
    val c =
      respec.menu(RespecRequest(world, actor("human", "warrior"), false), listOf("class")).single()
    val plain = c.toPlain()
    assertEquals(setOf("choice", "title", "current", "options"), plain.keys)
    @Suppress("UNCHECKED_CAST") val opts = plain["options"] as List<Map<String, Any?>>
    assertEquals(setOf("id", "label", "summary", "refusal"), opts[0].keys)
    val m =
      respec.check(RespecRequest(world, actor("human", "warrior"), false), "mage", listOf("class"))
    assertEquals(setOf("choice", "option", "label", "refusal"), m.toPlain().keys)
  }

  // ---- check --------------------------------------------------------------------------------

  @Test
  fun `check matches ids, labels, prefixes and choice-scoped phrases`() {
    val a = actor("human", "warrior")
    for (phrase in listOf("mage", "Mag", "class mage", "cl mage", "  CLASS   Mage ")) {
      assertEquals(
        RespecMatch("class", "mage", "Mage", null),
        respec.check(RespecRequest(world, a, false), phrase, listOf("race", "class")),
        phrase,
      )
    }
  }

  @Test
  fun `check with no words asks what to change`() {
    assertEquals(
      RespecMatch(null, null, null, "Change what?"),
      respec.check(RespecRequest(world, actor("human", "warrior"), false), "  ", listOf("class")),
    )
  }

  @Test
  fun `check finds nothing outside the shrine's choices`() {
    val a = actor("human", "warrior")
    val none = RespecMatch(null, null, null, "There's nothing like that to become here.")
    assertEquals(none, respec.check(RespecRequest(world, a, false), "xyz", listOf("race", "class")))
    assertEquals(none, respec.check(RespecRequest(world, a, false), "race elf", listOf("class")))
    assertEquals(none, respec.check(RespecRequest(world, a, false), "elf", listOf("class")))
  }

  @Test
  fun `check carries the option's refusal`() {
    assertEquals(
      RespecMatch("class", "mage", "Mage", "Mage isn't open to you."),
      respec.check(RespecRequest(world, actor("dwarf", "warrior"), false), "mage", listOf("class")),
    )
  }

  @Test
  fun `an ambiguous phrase lists the candidates`() {
    val wide = Respec(WideCreation())
    val a = actor("human", "warrior")
    assertEquals(
      RespecMatch(null, null, null, "Which do you mean: Elf (race), Elementalist (class)?"),
      wide.check(RespecRequest(world, a, false), "el", listOf("race", "class")),
    )
    assertEquals(
      RespecMatch("class", "elementalist", "Elementalist", "Elementalist isn't open to you."),
      wide.check(RespecRequest(world, a, false), "class el", listOf("race", "class")),
    )
  }

  @Test
  fun `an ambiguity within one choice names no titles`() {
    val wide = Respec(WideCreation())
    // "e" prefixes Elementalist only within class; across both it would also prefix Elf.
    assertEquals(
      RespecMatch("class", "elementalist", "Elementalist", "Elementalist isn't open to you."),
      wide.check(RespecRequest(world, actor("human", "warrior"), false), "e", listOf("class")),
    )
    val twoInClass =
      object : HeritageCreation() {
        override val choices =
          listOf(
            PluginChoice(
              "class",
              "Class",
              HeritagePlugin.CLASS_OPTIONS + PluginOption("monk", "Monk", "Quiet fists."),
            )
          )
      }
    assertEquals(
      "Which do you mean: Mage, Monk?",
      Respec(twoInClass)
        .check(RespecRequest(world, actor("human", "warrior"), false), "m", listOf("class"))
        .refusal,
    )
  }

  @Test
  fun `an exact match beats prefixes`() {
    val withMages =
      object : HeritageCreation() {
        override val choices =
          listOf(
            PluginChoice(
              "class",
              "Class",
              HeritagePlugin.CLASS_OPTIONS + PluginOption("magelord", "Magelord", "More."),
            )
          )
      }
    assertEquals(
      "mage",
      Respec(withMages)
        .check(RespecRequest(world, actor("human", "warrior"), false), "mage", listOf("class"))
        .option,
    )
  }

  // ---- apply --------------------------------------------------------------------------------

  @Test
  fun `apply sets exactly the one change`() {
    val a = actor("human", "warrior")
    assertNull(respec.apply(RespecRequest(world, a, false), "class", "mage"))
    assertEquals(
      listOf(mapOf<String, String?>("class" to "mage")),
      world.setChoicesCalls.map { it.second },
    )
  }

  @Test
  fun `a refused apply changes nothing`() {
    assertEquals(
      "Mage isn't open to you.",
      respec.apply(RespecRequest(world, actor("dwarf", "warrior"), false), "class", "mage"),
    )
    assertTrue(world.setChoicesCalls.isEmpty())
  }

  @Test
  fun `an allowed same pick changes nothing`() {
    assertNull(
      respec.apply(RespecRequest(world, actor("human", "warrior"), true), "class", "warrior")
    )
    assertTrue(world.setChoicesCalls.isEmpty())
  }

  // ---- starter skills -----------------------------------------------------------------------

  @Test
  fun `starter skills follow the class`() {
    assertEquals(
      listOf("//skills/heritage_skills:magic_missile"),
      creation.grantStarterSkills(world, actor("elf", "mage")),
    )
    assertEquals(listOf("//skills/heritage_skills:magic_missile" to 25), world.setSkillCalls)
    assertEquals(emptyList(), creation.grantStarterSkills(world, actor("human", "warrior")))
    assertEquals(emptyList(), creation.grantStarterSkills(world, FakeActor()))
  }

  @Test
  fun `a starter skill the actor knows is skipped`() {
    val a = actor("human", "cleric", skills = mapOf("//skills/heritage_skills:minor_heal" to 60))
    assertEquals(emptyList(), creation.grantStarterSkills(world, a))
    assertTrue(world.setSkillCalls.isEmpty())
  }

  @Test
  fun `a refused or undefined starter skill is skipped`() {
    world.skillAnswers["//skills/heritage_skills:magic_missile"] = "You can't learn that."
    assertEquals(emptyList(), creation.grantStarterSkills(world, actor("elf", "mage")))
    world.skillAnswers["//skills/heritage_skills:magic_missile"] = "undefined"
    assertEquals(emptyList(), creation.grantStarterSkills(world, actor("elf", "mage")))
  }

  @Test
  fun `created grants starter skills for re-picked characters too`() {
    creation.created(world, actor("human", "cleric"), newCharacter = false)
    assertEquals(listOf("//skills/heritage_skills:minor_heal" to 25), world.setSkillCalls)
  }

  // ---- builtins -----------------------------------------------------------------------------

  private fun builtins():
    Map<String, (gups.pluginapi.PluginWorld, List<Any?>, Map<String, Any?>) -> Any?> {
    val reg = FakeRegistry()
    HeritagePlugin().register(reg)
    return reg.builtins
  }

  @Test
  fun `the builtins return plain values`() {
    val b = builtins()
    val a = actor("human", "warrior")
    @Suppress("UNCHECKED_CAST")
    val menu =
      b.getValue("respec_menu")(world, listOf(a, listOf("class"), false), emptyMap())
        as List<Map<String, Any?>>
    assertEquals("class", menu.single()["choice"])
    @Suppress("UNCHECKED_CAST")
    val match =
      b.getValue("respec_check")(world, listOf(a, "mage", listOf("class"), false), emptyMap())
        as Map<String, Any?>
    assertEquals("mage", match["option"])
    assertNull(b.getValue("respec")(world, listOf(a, "class", "mage", false), emptyMap()))
    assertEquals(
      listOf("//skills/heritage_skills:magic_missile"),
      b.getValue("starter_skills")(world, listOf(actor("elf", "mage")), emptyMap()),
    )
  }

  @Test
  fun `allow_same reaches the check`() {
    val b = builtins()
    val a = actor("human", "warrior")
    @Suppress("UNCHECKED_CAST")
    val same =
      b.getValue("respec_check")(world, listOf(a, "warrior", listOf("class"), true), emptyMap())
        as Map<String, Any?>
    assertNull(same["refusal"])
    @Suppress("UNCHECKED_CAST")
    val refused =
      b.getValue("respec_check")(world, listOf(a, "warrior", listOf("class"), false), emptyMap())
        as Map<String, Any?>
    assertEquals("That is already your class.", refused["refusal"])
  }

  @Test
  fun `the builtins check their arguments`() {
    val b = builtins()
    val mob = FakeActor(isPlayer = false)
    assertEquals(
      "heritage.respec_menu(actor): expected a player",
      assertFailsWith<IllegalArgumentException> {
          b.getValue("respec_menu")(world, listOf(mob, listOf("class"), false), emptyMap())
        }
        .message,
    )
    assertEquals(
      "heritage.starter_skills(actor): expected a player",
      assertFailsWith<IllegalArgumentException> {
          b.getValue("starter_skills")(world, listOf("Bob"), emptyMap())
        }
        .message,
    )
    assertEquals(
      "heritage.respec_check(choices): expected a list of choice ids",
      assertFailsWith<IllegalArgumentException> {
          b.getValue("respec_check")(
            world,
            listOf(actor("human", "warrior"), "mage", "class", false),
            emptyMap(),
          )
        }
        .message,
    )
  }
}
