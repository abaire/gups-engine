package gups.core.world

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
import gups.core.model.Trait
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreationFlowTest {
  /** Race, class, then a school that only mages choose; dwarves can't be mages. */
  private class Heritage : CreationProvider {
    var refusal: ((Map<String, String>) -> String?) = { null }
    var failAvailable = false
    var failValidate = false
    var failStartRoom = false
    var start: Label? = null
    val created = mutableListOf<String>()

    override val choices =
      listOf(
        CreationChoice(
          "race",
          "Race",
          options =
            listOf(
              CreationOption("elf", "Elf", "Quick.", help = "Long-lived.", traits = listOf("keen")),
              CreationOption("dwarf", "Dwarf", "Hardy."),
              CreationOption("drow", "Dark Elf", "Shadowed."),
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
        CreationChoice(
          "school",
          "School",
          options = listOf(CreationOption("fire", "Fire", ""), CreationOption("frost", "Frost", "")),
        ),
      )

    override fun available(
      world: World,
      choice: String,
      answers: Map<String, String>,
    ): List<String> {
      check(!failAvailable) { "available broke" }
      val all = choices.first { it.id == choice }.options.map { it.id }
      return when (choice) {
        "class" -> if (answers["race"] == "dwarf") all - "mage" else all
        "school" -> if (answers["class"] == "mage") all else emptyList()
        else -> all
      }
    }

    override fun validate(world: World, answers: Map<String, String>): String? {
      check(!failValidate) { "validate broke" }
      return refusal(answers)
    }

    override fun startRoom(world: World, answers: Map<String, String>): Label? {
      check(!failStartRoom) { "startRoom broke" }
      return start
    }

    override fun created(world: World, player: Player, newCharacter: Boolean) {
      created +=
        "${player.name} new=$newCharacter playing=${player.session != null && player.inWorld}"
    }
  }

  /** Records text, prompts as `[prompt]`, and GMCP messages. */
  private class Sink(private val gmcp: Boolean) : OutputSink {
    val all = StringBuilder()
    private var mark = 0
    val messages = mutableListOf<Pair<String, Any?>>()
    var closed: String? = null

    override fun send(text: String) {
      all.append(text)
    }

    override fun prompt(text: String) {
      all.append("[$text]")
    }

    override fun gmcp(pkg: String, data: Any?) {
      messages += pkg to data
    }

    override fun wantsGmcp(pkg: String) = gmcp

    override fun close(reason: String) {
      closed = reason
    }

    fun drain(): String = all.substring(mark).also { mark = all.length }

    val creation: List<Any?>
      get() = messages.filter { it.first == "Char.Creation" }.map { it.second }
  }

  private val f = Fixture()
  private val w = f.w.also { it.login = DevLogin(T.HALL) }
  private val h = Heritage()
  private var nextId = 1L

  init {
    w.creation = h
    w.traits = mapOf("keen" to Trait("keen", statDeltas = mapOf(f.fs.stats.first() to 2)))
  }

  private inner class Conn(gmcp: Boolean = false) {
    val id = SessionId(nextId++)
    val sink = Sink(gmcp)

    init {
      w.handle(WorldControlEvent.Connect(id, sink, "127.0.0.1", "telnet"))
      sink.drain()
    }

    fun send(line: String): String {
      w.handle(ClientInputEvent(id, line, 0))
      return sink.drain()
    }

    val player: Player?
      get() = w.players.firstOrNull { it.session == id }
  }

  private val namePrompt = "[By what name do you wish to be known? ]"

  @Test
  fun `the first choice lists the options numbered with labels padded, then the hint`() {
    val out = Conn().send("Ann")
    assertEquals(
      "Choose your race:\n" +
        "  1. Elf        Quick.\n" +
        "  2. Dwarf      Hardy.\n" +
        "  3. Dark Elf   Shadowed.\n" +
        "Type a number or a name, \"help <name>\" to learn more, \"back\" or \"cancel\".\n" +
        "[> ]",
      out,
    )
  }

  @Test
  fun `answers match by label, unique prefix, id and number`() {
    val c = Conn()
    c.send("Ann")
    assertContains(c.send("dark elf"), "Choose your class:")
    assertContains(c.send("MA"), "Choose your school:")
    assertContains(
      c.send("frost"),
      "Ann: Race Dark Elf, Class Mage, School Frost. Create this character? (yes, back or cancel)",
    )
    assertContains(c.send("back"), "Choose your school:")
    assertContains(c.send("1"), "School Fire.")
    assertContains(c.send("y"), "Welcome, Ann.")
    assertEquals(
      mapOf("race" to "drow", "class" to "mage", "school" to "fire"),
      c.player!!.choices.toMap(),
    )
  }

  @Test
  fun `an ambiguous prefix, an unknown answer or a number out of range asks again`() {
    val c = Conn()
    c.send("Ann")
    for (answer in listOf("d", "orc", "4", "0")) {
      val out = c.send(answer)
      assertContains(out, "There's no $answer to choose here.\nChoose your race:\n  1. Elf")
      assertTrue(out.endsWith("[> ]"), out)
    }
    assertContains(c.send("dw"), "Choose your class:", message = "a unique prefix still works")
  }

  @Test
  fun `help with a name shows the option's help or summary, and help alone shows the hint`() {
    val c = Conn()
    c.send("Ann")
    assertEquals("Elf\nLong-lived.\n[> ]", c.send("help elf"))
    assertEquals("Dwarf\nHardy.\n[> ]", c.send("help 2"))
    assertEquals(
      "Type a number or a name, \"help <name>\" to learn more, \"back\" or \"cancel\".\n[> ]",
      c.send("help"),
    )
    assertEquals("There's no orc to choose here.\n[> ]", c.send("help orc"))
    assertContains(c.send("elf"), "Choose your class:", message = "help leaves the step as it was")
  }

  @Test
  fun `back returns to each earlier step, and from the first choice to the name prompt`() {
    val c = Conn()
    c.send("Ann")
    c.send("elf")
    c.send("mage")
    c.send("fire")
    assertContains(c.send("back"), "Choose your school:", message = "from the confirmation")
    assertContains(c.send("back"), "Choose your class:")
    assertContains(c.send("back"), "Choose your race:")
    assertEquals(namePrompt, c.send("back"))
    assertTrue(w.players.isEmpty())
    assertContains(c.send("Bea"), "Choose your race:")
  }

  @Test
  fun `back from the confirmation skips a choice that wasn't asked`() {
    val c = Conn()
    c.send("Ann")
    c.send("elf")
    assertContains(c.send("warrior"), "Race Elf, Class Warrior. Create")
    assertContains(c.send("back"), "Choose your class:")
  }

  @Test
  fun `cancel at every step creates nothing and asks for a name again`() {
    val steps =
      listOf(listOf(), listOf("elf"), listOf("elf", "mage"), listOf("elf", "mage", "fire"))
    for (answers in steps) {
      val c = Conn()
      c.send("Ann")
      answers.forEach { c.send(it) }
      assertEquals(namePrompt, c.send("cancel"), "cancel after $answers")
      assertTrue(w.players.isEmpty())
    }
    assertTrue(h.created.isEmpty())
  }

  @Test
  fun `answering an earlier choice again clears later answers and changes what is offered`() {
    val c = Conn()
    c.send("Ann")
    c.send("elf")
    c.send("mage")
    c.send("back")
    c.send("back")
    val classes = c.send("dwarf")
    assertContains(classes, "Choose your class:\n  1. Warrior   Strong.\nType a number")
    assertFalse("Mage" in classes)
    assertContains(c.send("1"), "Ann: Race Dwarf, Class Warrior. Create")
    c.send("yes")
    assertEquals(mapOf("race" to "dwarf", "class" to "warrior"), c.player!!.choices.toMap())
  }

  @Test
  fun `a validation refusal returns to the last choice with the message`() {
    h.refusal = {
      if (it["race"] == "elf" && it["class"] == "warrior") "Elves won't fight." else null
    }
    val c = Conn()
    c.send("Ann")
    c.send("elf")
    c.send("warrior")
    val out = c.send("yes")
    assertContains(out, "Elves won't fight.\nChoose your class:")
    assertTrue(w.players.isEmpty())
    c.send("mage")
    c.send("frost")
    assertContains(c.send("yes"), "Welcome, Ann.")
  }

  @Test
  fun `the new character has its answers and traits, starts in the provider's room, and created runs after it enters`() {
    h.start = T.YARD
    val c = Conn()
    c.send("Ann")
    c.send("elf")
    c.send("warrior")
    c.send("yes")
    val p = c.player!!
    assertEquals(T.YARD, p.room.label)
    assertEquals(mapOf("race" to "elf", "class" to "warrior"), p.choices.toMap())
    assertEquals(listOf("keen"), p.traits.map { it.name })
    val stat = f.fs.stats.first()
    assertEquals(p.baseStat(stat) + 2, p.stat(stat))
    assertEquals(listOf("Ann new=true playing=true"), h.created)
  }

  @Test
  fun `an unknown start room uses the default with a diagnostic`() {
    h.start = Label("//t:nowhere")
    val c = Conn()
    c.send("Ann")
    c.send("dwarf")
    c.send("warrior")
    c.send("yes")
    assertEquals(T.HALL, c.player!!.room.label)
    assertTrue(w.diagnostics.any { it.kind == "plugin" && "//t:nowhere" in it.message })
  }

  @Test
  fun `a provider that throws asks for a name again with the message and a diagnostic`() {
    val failed = "Character creation isn't working right now; please tell an instructor."
    h.failAvailable = true
    assertEquals("$failed\n$namePrompt", Conn().send("Ann"))
    h.failAvailable = false

    h.failValidate = true
    val c = Conn()
    c.send("Ann")
    c.send("dwarf")
    c.send("warrior")
    assertEquals("$failed\n$namePrompt", c.send("yes"))
    h.failValidate = false

    h.failStartRoom = true
    val d = Conn()
    d.send("Bea")
    d.send("dwarf")
    d.send("warrior")
    assertEquals("$failed\n$namePrompt", d.send("yes"))
    assertTrue(w.players.isEmpty())
    val messages = w.diagnostics.filter { it.kind == "plugin" }.map { it.message }
    for (what in listOf("available broke", "validate broke", "startRoom broke")) assertTrue(
      messages.any { what in it },
      "$messages",
    )
  }

  @Suppress("UNCHECKED_CAST")
  @Test
  fun `Char Creation describes each step and the confirmation, then null once`() {
    val c = Conn(gmcp = true)
    c.send("Ann")
    c.send("elf")
    c.send("warrior")
    c.send("yes")
    val payloads = c.sink.creation
    assertEquals(4, payloads.size, "$payloads")
    val race = payloads[0] as Map<String, Any?>
    assertEquals("Ann", race["name"])
    assertEquals("choice", race["step"])
    val choice = race["choice"] as Map<String, Any?>
    assertEquals("race", choice["id"])
    assertEquals("Race", choice["title"])
    assertEquals("Choose your race:", choice["prompt"])
    assertEquals(
      mapOf("id" to "elf", "label" to "Elf", "summary" to "Quick.", "help" to "Long-lived."),
      (choice["options"] as List<Any?>).first(),
    )
    assertEquals(
      listOf("elf", "dwarf", "drow"),
      (choice["options"] as List<Map<String, Any?>>).map { it["id"] },
    )
    assertEquals(emptyList<Any?>(), race["answers"])

    val klass = payloads[1] as Map<String, Any?>
    assertEquals("class", (klass["choice"] as Map<String, Any?>)["id"])
    assertEquals(
      listOf(mapOf("choice" to "race", "title" to "Race", "option" to "elf", "label" to "Elf")),
      klass["answers"],
    )

    val confirm = payloads[2] as Map<String, Any?>
    assertEquals("confirm", confirm["step"])
    assertTrue(confirm.containsKey("choice") && confirm["choice"] == null)
    assertEquals(2, (confirm["answers"] as List<Any?>).size)

    assertNull(payloads[3])
    c.send("look")
    assertEquals(4, c.sink.creation.size, "null is sent once")
  }

  @Test
  fun `Char Creation ends with null on cancel too, and isn't sent to clients without it`() {
    val c = Conn(gmcp = true)
    c.send("Ann")
    c.send("cancel")
    assertEquals(2, c.sink.creation.size)
    assertNull(c.sink.creation.last())

    val plain = Conn(gmcp = false)
    plain.send("Bea")
    plain.send("elf")
    plain.send("warrior")
    plain.send("yes")
    assertTrue(plain.sink.messages.none { it.first == "Char.Creation" })
  }

  @Test
  fun `without a provider the dev login creates the character at once`() {
    w.creation = null
    val c = Conn()
    val out = c.send("Ann")
    assertTrue(out.startsWith("Welcome, Ann.\nThe Hall\n"), out)
    assertFalse("Choose" in out)
    assertTrue(c.sink.messages.none { it.first == "Char.Creation" })
    assertEquals(emptyMap(), c.player!!.choices.toMap())
  }

  @Test
  fun `a saved character taken over in the world isn't asked its choices`() {
    w.creation = null
    Conn().send("Ann")
    w.creation = h
    val c = Conn()
    val out = c.send("Ann")
    assertFalse("choices to make" in out, out)
    assertEquals(c.id, w.players.single().session)
  }

  private class StepsProvider(
    override val choices: List<CreationChoice> =
      listOf(
        CreationChoice("race", "Race", options = listOf(CreationOption("human", "Human", "")))
      ),
    override val steps: List<CreationStep> = emptyList(),
  ) : CreationProvider {
    override fun available(
      world: World,
      choice: String,
      answers: Map<String, String>,
    ): List<String> = choices.first { it.id == choice }.options.map { it.id }

    override fun validate(world: World, answers: Map<String, String>): String? = null

    override fun startRoom(world: World, answers: Map<String, String>): Label? = null

    override fun created(world: World, player: Player, newCharacter: Boolean) {}
  }

  private class TestStep(
    override val id: String,
    override val title: String,
    var appliesResult: (CreationAttempt) -> Boolean = { true },
    var showResult: (CreationAttempt) -> StepView = { StepView("$id text", mapOf("step" to id)) },
    var inputResult: (CreationAttempt, String) -> StepReply = { _, line ->
      if (line == "no") StepReply.Stay("No.") else StepReply.Done
    },
    var summaryResult: (CreationAttempt) -> String? = { "$id summary" },
  ) : CreationStep {
    var shown = 0

    override fun applies(world: World, attempt: CreationAttempt) = appliesResult(attempt)

    override fun show(world: World, attempt: CreationAttempt): StepView {
      shown++
      return showResult(attempt)
    }

    override fun input(world: World, attempt: CreationAttempt, line: String) =
      inputResult(attempt, line)

    override fun summary(world: World, attempt: CreationAttempt) = summaryResult(attempt)
  }

  @Test
  @Suppress("UNCHECKED_CAST")
  fun `custom steps flow through show, input, stay, summaries, gmcp and back navigation`() {
    val step1 = TestStep("step1", "Step One")
    val step2 = TestStep("step2", "Step Two")
    val provider = StepsProvider(steps = listOf(step1, step2))
    w.creation = provider

    val c = Conn(gmcp = true)
    c.send("Hero") // Asks race choice
    val out1 = c.send("human") // Answer choice, moves to step 1
    assertEquals("step1 text\n[> ]", out1)

    // Check GMCP payload for step 1
    val gmcpStep1 = c.sink.creation.last() as Map<String, Any?>
    assertEquals("Hero", gmcpStep1["name"])
    assertEquals("custom", gmcpStep1["step"])
    assertNull(gmcpStep1["choice"])
    assertEquals(
      mapOf("id" to "step1", "title" to "Step One", "data" to mapOf("step" to "step1")),
      gmcpStep1["custom"],
    )
    assertEquals(
      listOf(mapOf("choice" to "race", "title" to "Race", "option" to "human", "label" to "Human")),
      gmcpStep1["answers"],
    )
    assertEquals(emptyList<Any?>(), gmcpStep1["summaries"])

    // Stay sends message then text again
    val outStay = c.send("no")
    assertEquals("No.\nstep1 text\n[> ]", outStay)

    // Done advances to step 2
    step1.showResult = { attempt ->
      attempt.state["step1_done"] = true
      StepView("step1 advanced", mapOf("step" to "step1"))
    }
    val outDone1 = c.send("done")
    assertEquals("step2 text\n[> ]", outDone1)

    // Step 2 done advances to confirm
    val outConfirm = c.send("done")
    assertTrue(
      outConfirm.contains("Race Human, Step One step1 summary, Step Two step2 summary"),
      outConfirm,
    )

    val gmcpConfirm = c.sink.creation.last() as Map<String, Any?>
    assertEquals("confirm", gmcpConfirm["step"])
    assertNull(gmcpConfirm["custom"])
    assertEquals(
      listOf(
        mapOf("step" to "step1", "title" to "Step One", "text" to "step1 summary"),
        mapOf("step" to "step2", "title" to "Step Two", "text" to "step2 summary"),
      ),
      gmcpConfirm["summaries"],
    )

    // back from confirm returns to step 2 with step 1 still finished
    val outBackConfirm = c.send("back")
    assertEquals("step2 text\n[> ]", outBackConfirm)

    // back from step 2 returns to step 1
    val outBackStep2 = c.send("back")
    assertEquals("step1 advanced\n[> ]", outBackStep2)

    // back from step 1 returns to choice
    val outBackStep1 = c.send("back")
    assertTrue(outBackStep1.contains("Choose your race:"), outBackStep1)

    // Re-answering race choice clears state and finished, so step 1 runs again
    val outReanswer = c.send("human")
    assertEquals("step1 advanced\n[> ]", outReanswer)
  }

  @Test
  fun `unapplicable steps are skipped`() {
    val step1 = TestStep("step1", "Step One", appliesResult = { false })
    val step2 = TestStep("step2", "Step Two", appliesResult = { true })
    w.creation = StepsProvider(steps = listOf(step1, step2))

    val c = Conn()
    c.send("Hero")
    val out = c.send("human")
    assertEquals("step2 text\n[> ]", out)
  }

  @Test
  fun `throwing step ends with ProviderFailed and diagnostic`() {
    for (fault in listOf("show", "input", "summary")) {
      val step = TestStep("step1", "Step One")
      when (fault) {
        "show" -> step.showResult = { throw RuntimeException("show boom") }
        "input" -> step.inputResult = { _, _ -> throw RuntimeException("input boom") }
        "summary" -> step.summaryResult = { throw RuntimeException("summary boom") }
      }
      w.creation = StepsProvider(steps = listOf(step))
      val c = Conn()
      c.send("Hero$fault")
      val out = c.send("human")
      if (fault == "show") {
        assertTrue(out.contains("Character creation isn't working right now"), out)
      } else if (fault == "input") {
        val outInput = c.send("something")
        assertTrue(outInput.contains("Character creation isn't working right now"), outInput)
      } else if (fault == "summary") {
        val outDone = c.send("ok")
        assertTrue(outDone.contains("Character creation isn't working right now"), outDone)
      }
      assertTrue(w.diagnostics.any { it.kind == "plugin" && it.message.contains(fault) })
    }
  }

  @Test
  fun `provider with steps and no choices goes straight to steps`() {
    val step = TestStep("only", "Only Step")
    w.creation = StepsProvider(choices = emptyList(), steps = listOf(step))

    val c = Conn()
    val out = c.send("NoChoicesHero")
    assertEquals("only text\n[> ]", out)

    // back from only step goes to name prompt
    val outBack = c.send("back")
    assertEquals(namePrompt, outBack)
  }

  private class SavedChoices(val saved: MutableMap<String, Map<String, String>>) : Persistence {
    override fun checkpoint(world: World, cause: String, immediate: Boolean) {}

    override fun savedCharacter(name: String) = saved[name]?.let { SavedCharacter(name, null) }

    override fun loadCharacter(world: World, name: String): Player? {
      val choices = saved[name] ?: return null
      return world.spawnPlayer(name, T.HALL, choices = choices)
    }
  }

  @Test
  fun `newCharacter false never shows steps`() {
    val step = TestStep("step1", "Step One")
    val prov =
      StepsProvider(
        choices =
          listOf(
            CreationChoice("race", "Race", options = listOf(CreationOption("human", "Human", ""))),
            CreationChoice(
              "class",
              "Class",
              options = listOf(CreationOption("warrior", "Warrior", "")),
            ),
          ),
        steps = listOf(step),
      )
    w.creation = prov
    w.persistence = SavedChoices(mutableMapOf("Hero" to mapOf("race" to "human")))

    // Login Hero - missing choices should be asked, but custom steps should NOT be run
    val c = Conn()
    val outLogin = c.send("Hero")
    assertTrue(outLogin.contains("Choose your class:"), outLogin)
    val outAnswer = c.send("warrior")
    // Should go straight to confirm without showing step1
    assertTrue(outAnswer.contains("Race Human, Class Warrior. Create this character?"), outAnswer)
    assertFalse("step1 text" in outAnswer)
  }

  @Test
  fun `runStepsHeadless returns Finished and marks steps finished when all applicable steps complete`() {
    val step1 = TestStep("s1", "Step 1")
    val step2 = TestStep("s2", "Step 2")
    w.creation = StepsProvider(steps = listOf(step1, step2))
    val attempt = CreationAttempt("Bob", CreationAttempt.nameKey("Bob"), 0, 123L)

    val result =
      w.runStepsHeadless(attempt) { stepId ->
        when (stepId) {
          "s1" -> listOf("ok")
          "s2" -> listOf("invalid", "ok")
          else -> emptyList()
        }
      }

    assertEquals(HeadlessSteps.Finished, result)
    assertEquals(setOf("s1", "s2"), attempt.finished)
    assertEquals(1, step1.shown)
    assertEquals(1, step2.shown)
  }

  @Test
  fun `runStepsHeadless skips non-applicable steps`() {
    val step1 = TestStep("s1", "Step 1", appliesResult = { false })
    val step2 = TestStep("s2", "Step 2", appliesResult = { true })
    w.creation = StepsProvider(steps = listOf(step1, step2))
    val attempt = CreationAttempt("Bob", CreationAttempt.nameKey("Bob"), 0, 123L)

    val result = w.runStepsHeadless(attempt) { listOf("ok") }

    assertEquals(HeadlessSteps.Finished, result)
    assertEquals(setOf("s2"), attempt.finished)
    assertEquals(0, step1.shown)
    assertEquals(1, step2.shown)
  }

  @Test
  fun `runStepsHeadless returns Unfinished when lines run out before Done`() {
    val step1 =
      TestStep(
        "s1",
        "Step 1",
        inputResult = { _, line ->
          if (line == "stay") StepReply.Stay("staying") else StepReply.Done
        },
      )
    val step2 = TestStep("s2", "Step 2")
    w.creation = StepsProvider(steps = listOf(step1, step2))
    val attempt = CreationAttempt("Bob", CreationAttempt.nameKey("Bob"), 0, 123L)

    val result =
      w.runStepsHeadless(attempt) { stepId ->
        when (stepId) {
          "s1" -> listOf("stay")
          else -> listOf("ok")
        }
      }

    assertEquals(HeadlessSteps.Unfinished("s1"), result)
    assertEquals(emptySet(), attempt.finished)
    assertEquals(0, step2.shown)
  }

  @Test
  fun `runStepsHeadless returns Failed when step throws in applies show or input`() {
    val stepThrowsShow =
      object : CreationStep {
        override val id = "err"
        override val title = "Err"

        override fun applies(world: World, attempt: CreationAttempt) = true

        override fun show(world: World, attempt: CreationAttempt): StepView = error("boom show")

        override fun input(world: World, attempt: CreationAttempt, line: String): StepReply =
          StepReply.Done

        override fun summary(world: World, attempt: CreationAttempt): String? = null
      }
    w.creation = StepsProvider(steps = listOf(stepThrowsShow))
    val attempt = CreationAttempt("Bob", CreationAttempt.nameKey("Bob"), 0, 123L)

    val res1 = w.runStepsHeadless(attempt) { listOf("ok") }
    assertTrue(res1 is HeadlessSteps.Failed)
    assertEquals("err", res1.stepId)
    assertEquals("boom show", res1.error.message)

    val stepThrowsInput =
      object : CreationStep {
        override val id = "err2"
        override val title = "Err 2"

        override fun applies(world: World, attempt: CreationAttempt) = true

        override fun show(world: World, attempt: CreationAttempt) = StepView("text")

        override fun input(world: World, attempt: CreationAttempt, line: String): StepReply =
          error("boom input")

        override fun summary(world: World, attempt: CreationAttempt): String? = null
      }
    w.creation = StepsProvider(steps = listOf(stepThrowsInput))
    val res2 = w.runStepsHeadless(attempt) { listOf("ok") }
    assertTrue(res2 is HeadlessSteps.Failed)
    assertEquals("err2", res2.stepId)
    assertEquals("boom input", res2.error.message)
  }
}
